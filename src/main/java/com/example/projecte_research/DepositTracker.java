package com.example.projecte_research;

import com.mojang.logging.LogUtils;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.ProjectEAPI;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.emc.nbt.NBTManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Универсальное распознавание "вклада предметов" (экспорт в EMC Link, интерфейс
 * трансмутации, любой другой блок/шина/труба любого мода) БЕЗ привязки к классам мода.
 *
 * Что общего у всех таких источников. Какой бы мод ни был, внутри ОДНОЙ цепочки вызовов
 * (один insertItem / один метод блока) происходит ровно два события в ProjectE:
 *   1) provider.addKnowledge(...)  - предмет выучен;
 *   2) provider.setEmc(старое + цена * количество) - начислен EMC.
 * Оба события приходят от одного и того же кода (одного мода) и для одного игрока.
 *
 * Поэтому:
 *  - выученный "из ниоткуда" предмет мы записываем как Open (оптимистично даём выучить);
 *  - количество предметов берём из EMC: прирост / цена предмета (EMC - правда о количестве);
 *  - дальше действуем как стол трансмутации: считаем прогресс, при нехватке забираем
 *    знание обратно, а EMC (если включён noEmcWhileLearning) режем пропорционально.
 *  - порядок событий не важен (сначала знание или сначала EMC).
 *
 * Чтобы не ломать другие моды, всё срабатывает только если ВСЕ условия выполнены:
 *  - мы не внутри явной обработки (стол, Expansion, AppliedE - там уже есть точный учёт);
 *  - оба события от одного пакета мода (см. origin()) и для одного игрока;
 *  - события в окне 100 мс;
 *  - прирост EMC ровно кратен цене предмета.
 * Всё остальное (Tome of Knowledge, команды, коллекторы, чужие начисления EMC) проходит
 * без изменений.
 */
public final class DepositTracker {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Окно, в котором знание и прирост EMC считаются одной операцией. */
    private static final long WINDOW_NANOS = 100_000_000L;
    private static final int MAX_LIST = 16;

    private static final String[] INFRA = {
            "java.", "jdk.", "sun.", "net.minecraft.", "net.minecraftforge.", "cpw.mods.",
            "org.spongepowered.", "com.mojang.", "moze_intel.projecte.",
            "com.example.projecte_research.", "it.unimi.", "com.google.", "org.apache.",
            "org.objectweb.", "org.slf4j.", "io.netty."
    };

    /** Предмет, выученный сторонним кодом, который ещё не "рассчитан" по счётчику. */
    private static final class Open {
        final IKnowledgeProvider provider;
        final ServerPlayer owner;
        final ItemInfo rawInfo;   // как пришёл (для цены)
        final ItemInfo info;      // "уменьшенный" ключ знания
        final String origin;
        final long hint;          // количество из стака (если вызвали addKnowledge(ItemStack)), иначе 0
        final long nano;
        boolean kept = true;      // знание сейчас выдано
        boolean done;             // порог достигнут в этой операции
        long credited;            // сколько предметов уже учтено по EMC

        Open(IKnowledgeProvider provider, ServerPlayer owner, ItemInfo rawInfo, ItemInfo info,
             String origin, long hint, long nano) {
            this.provider = provider;
            this.owner = owner;
            this.rawInfo = rawInfo;
            this.info = info;
            this.origin = origin;
            this.hint = hint;
            this.nano = nano;
        }
    }

    /** Уже случившийся прирост EMC (на случай, если EMC начислили раньше знания). */
    private static final class Gain {
        final IKnowledgeProvider provider;
        final String origin;
        final BigInteger delta;
        final BigInteger baseline;
        final long nano;

        Gain(IKnowledgeProvider provider, String origin, BigInteger delta, BigInteger baseline, long nano) {
            this.provider = provider;
            this.origin = origin;
            this.delta = delta;
            this.baseline = baseline;
            this.nano = nano;
        }
    }

    private static final class State {
        final List<Open> opens = new ArrayList<>();
        final List<Gain> gains = new ArrayList<>();
        int bypass;
        long hint;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private DepositTracker() {}

    // ---------------------------------------------------------------- служебное

    /** Наши собственные вызовы ProjectE не должны попадать в учёт. */
    public static boolean isBypassed() {
        return STATE.get().bypass > 0;
    }

    public static void bypass(Runnable action) {
        State s = STATE.get();
        s.bypass++;
        try {
            action.run();
        } finally {
            s.bypass--;
        }
    }

    /** Мы внутри явного учёта (стол, Expansion, AppliedE) - там точное количество уже известно. */
    private static boolean explicit() {
        return EmcSuppression.explicitActive() || ContributionManager.EXTERNAL_AMOUNT.get() != null;
    }

    /** Количество предметов из стака, если источник вызвал addKnowledge(ItemStack). */
    public static void setHint(long hint) {
        STATE.get().hint = Math.max(0, hint);
    }

    public static void clearHint() {
        STATE.get().hint = 0;
    }

    private static boolean debug() {
        return ResearchConfig.debugFlow();
    }

    // ---------------------------------------------------------------- "кто вызвал"

    private static boolean isInfra(String className) {
        for (String prefix : INFRA) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Пакет первого "чужого" класса в стеке: например "dev.author.somemod". Пусто - вызвал сам ProjectE/игра. */
    static String origin() {
        return StackWalker.getInstance().walk(frames -> frames
                .limit(24)
                .map(StackWalker.StackFrame::getClassName)
                .filter(name -> !isInfra(name))
                .findFirst()
                .map(DepositTracker::modKey)
                .orElse(""));
    }

    private static String modKey(String className) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? className : className.substring(0, dot);
        String[] parts = pkg.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- цена предмета

    private static long unitValue(ItemInfo info) {
        try {
            long v = ProjectEAPI.getEMCProxy().getValue(info);
            return Math.max(0, v);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- события ProjectE

    /**
     * Вызывается после успешного provider.addKnowledge(ItemInfo).
     * @return false - знание нужно забрать обратно (вернуть из addKnowledge false)
     */
    public static boolean onLearned(IKnowledgeProvider provider, ServerPlayer owner, ItemInfo rawInfo) {
        State s = STATE.get();
        long hint = s.hint;
        s.hint = 0;
        if (owner == null || s.bypass > 0 || explicit()) return true;

        String origin = origin();
        if (origin.isEmpty()) return true; // Tome, команды и т.п.: вызвал сам ProjectE

        ItemInfo info = NBTManager.getPersistentInfo(rawInfo);
        boolean noEmc = ResearchConfig.current().noEmcWhileLearning();
        int required = ResearchConfig.current().requiredFor(info.getItem());
        if (!noEmc && required <= 1 && hint <= 1) return true; // учитывать нечего

        long now = System.nanoTime();
        Open open = new Open(provider, owner, rawInfo, info, origin, hint, now);
        if (debug()) {
            LOGGER.info("[flow] learn {} origin={} hint={} required={}", info.getItem(), origin, hint, required);
        }

        // Порядок "сначала EMC, потом знание": разбираем уже случившиеся приросты.
        for (Iterator<Gain> it = s.gains.iterator(); it.hasNext(); ) {
            Gain g = it.next();
            if (g.provider != provider || !g.origin.equals(origin) || now - g.nano > WINDOW_NANOS) continue;
            if (settle(open, g.delta, true, g.baseline) != null) it.remove();
        }

        add(s.opens, open);
        return open.kept;
    }

    /**
     * Вызывается при каждой записи EMC игроку вне явных сеансов.
     * @return значение, которое нужно реально записать
     */
    public static BigInteger onSetEmc(IKnowledgeProvider provider, ServerPlayer owner, BigInteger newEmc) {
        if (owner == null) return newEmc;
        State s = STATE.get();
        if (s.bypass > 0 || explicit()) return newEmc;

        BigInteger now = provider.getEmc();
        if (newEmc.compareTo(now) <= 0) return newEmc; // списание не трогаем
        BigInteger delta = newEmc.subtract(now);

        String origin = origin();
        if (origin.isEmpty()) return newEmc;

        long t = System.nanoTime();
        for (Open open : s.opens) {
            if (open.provider != provider || !open.origin.equals(origin) || t - open.nano > WINDOW_NANOS) continue;
            BigInteger allowed = settle(open, delta, false, now);
            if (allowed != null) return now.add(allowed);
        }

        if (debug()) {
            LOGGER.info("[flow] gain +{} origin={} (no matching learn yet)", delta, origin);
        }
        add(s.gains, new Gain(provider, origin, delta, now, t));
        return newEmc;
    }

    /** В конце тика: источники, которые ничего не начислили, считаем по количеству из стака. */
    public static void flush() {
        State s = STATE.get();
        if (s.opens.isEmpty() && s.gains.isEmpty()) return;
        List<Open> opens = new ArrayList<>(s.opens);
        s.opens.clear();
        s.gains.clear();

        for (Open open : opens) {
            if (open.credited > 0 || open.hint <= 0) continue;
            ContributionManager.Result r = ContributionManager.addProgressResult(open.owner, open.info, open.hint);
            if (debug()) {
                LOGGER.info("[flow] flush {} hint={} learned={}", open.info.getItem(), open.hint, r.learned());
            }
            if (r.learned()) grant(open); else revoke(open);
        }
    }

    // ---------------------------------------------------------------- расчёт

    /**
     * Учитывает один прирост EMC как вклад предметов.
     * @param applied true - EMC уже записан (нужен откат), false - ещё не записан (вернуть значение)
     * @return сколько из прироста оставить, или null, если прирост не похож на вклад этого предмета
     */
    private static BigInteger settle(Open o, BigInteger delta, boolean applied, BigInteger baseline) {
        long unit = unitValue(o.rawInfo);
        if (unit <= 0) return null;
        BigInteger[] qr = delta.divideAndRemainder(BigInteger.valueOf(unit));
        if (qr[1].signum() != 0 || qr[0].signum() <= 0) return null; // не кратно цене - не наш случай

        long k = qr[0].bitLength() > 30 ? Integer.MAX_VALUE : qr[0].longValue();
        o.credited += k;

        BigInteger allowed;
        if (o.done) {
            allowed = delta; // порог уже набран в этой операции: остальное - лишние предметы
        } else {
            ContributionManager.Result r = ContributionManager.addProgressResult(o.owner, o.info, k);
            if (r.learned()) {
                o.done = true;
                grant(o);
            } else {
                revoke(o);
            }
            if (ResearchConfig.current().noEmcWhileLearning()) {
                long excess = Math.min(r.excess(), k);
                allowed = delta.multiply(BigInteger.valueOf(excess)).divide(BigInteger.valueOf(k));
            } else {
                allowed = delta;
            }
        }

        if (debug()) {
            LOGGER.info("[flow] settle {} count={} unit={} allowedEmc={}/{} kept={} applied={}",
                    o.info.getItem(), k, unit, allowed, delta, o.kept, applied);
        }

        if (applied) {
            BigInteger remove = delta.subtract(allowed);
            if (remove.signum() > 0) {
                bypass(() -> {
                    BigInteger cur = o.provider.getEmc();
                    BigInteger target = cur.subtract(remove).max(baseline);
                    if (target.compareTo(cur) < 0) {
                        o.provider.setEmc(target);
                        o.provider.syncEmc(o.owner); // если не компилируется - замените на provider.sync(o.owner)
                    }
                });
            }
        }
        return allowed;
    }

    private static void grant(Open o) {
        if (!o.provider.hasKnowledge(o.info)) {
            bypass(() -> o.provider.addKnowledge(o.rawInfo));
            o.provider.syncKnowledgeChange(o.owner, o.info, true);
        }
        o.kept = true;
    }

    private static void revoke(Open o) {
        if (o.provider.hasKnowledge(o.info)) {
            o.provider.removeKnowledge(o.info);
            o.provider.syncKnowledgeChange(o.owner, o.info, false);
        }
        o.kept = false;
    }

    private static <T> void add(List<T> list, T value) {
        if (list.size() >= MAX_LIST) list.remove(0);
        list.add(value);
    }
}
