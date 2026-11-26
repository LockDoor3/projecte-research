package com.example.projecte_research;

import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Опция noEmcWhileLearning: предметы, которые идут в изучение, не дают EMC при вставке
 * в стол трансмутации, EMC Link, терминал AE2 (AppliedE) и т.д.
 * Если вставлено больше, чем нужно для изучения, за лишние предметы EMC начисляется как обычно.
 *
 * Точки входа (клик в столе, insertItem у Expansion и AppliedE) открывают "сеанс"
 * через begin() и закрывают через end(). Внутри сеанса:
 *  - ContributionManager.addProgress вызывает markLearning(всего, лишних);
 *  - любое начисление EMC идёт через setEmc -> onSetEmc(): если предмет уже помечен как
 *    изучаемый, пропускаем только долю прироста, равную лишним/всего;
 *  - если EMC начислили ДО пометки, end() убирает лишнюю часть задним числом.
 */
public final class EmcSuppression {
    private static final class Baseline {
        final ServerPlayer owner;
        final BigInteger emc;
        /** Прирост EMC, начисленный до того, как предмет пометили изучаемым. */
        BigInteger raw = BigInteger.ZERO;

        Baseline(ServerPlayer owner, BigInteger emc) {
            this.owner = owner;
            this.emc = emc;
        }
    }

    private static final class Scope {
        final long start = System.nanoTime();
        final Map<IKnowledgeProvider, Baseline> baselines = new IdentityHashMap<>();
        int depth;
        boolean learning;
        long amount;  // сколько предметов вставлено
        long excess;  // из них сверх нужного для изучения
    }

    /** Сеанс, который не закрыли за это время (например, из-за исключения), считается утёкшим. */
    private static final long MAX_AGE_NANOS = 2_000_000_000L;

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    private EmcSuppression() {}

    private static Scope current() {
        Scope s = SCOPE.get();
        if (s != null && System.nanoTime() - s.start > MAX_AGE_NANOS) {
            SCOPE.remove();
            return null;
        }
        return s;
    }

    /** Доля прироста, которая положена за лишние предметы. */
    private static BigInteger allowed(Scope s, BigInteger delta) {
        if (s.amount <= 0 || s.excess <= 0) return BigInteger.ZERO;
        return delta.multiply(BigInteger.valueOf(s.excess)).divide(BigInteger.valueOf(s.amount));
    }

    public static void begin() {
        if (!ResearchConfig.current().noEmcWhileLearning()) return;
        Scope s = current();
        if (s == null) {
            s = new Scope();
            SCOPE.set(s);
        }
        s.depth++;
    }

    /** amount - сколько предметов вставлено, excess - сколько из них сверх нужного для изучения. */
    public static void markLearning(int amount, int excess) {
        Scope s = current();
        if (s == null) return;
        s.learning = true;
        s.amount += Math.max(0, amount);
        s.excess += Math.max(0, Math.min(excess, amount));
    }

    /** Вызывается из setEmc. Возвращает значение, которое нужно реально записать. */
    public static BigInteger onSetEmc(IKnowledgeProvider provider, ServerPlayer owner, BigInteger newEmc) {
        Scope s = current();
        if (s == null) return newEmc;
        BigInteger now = provider.getEmc();
        Baseline b = s.baselines.computeIfAbsent(provider, p -> new Baseline(owner, now));
        if (newEmc.compareTo(now) <= 0) return newEmc; // списание EMC не трогаем

        BigInteger delta = newEmc.subtract(now);
        if (s.learning) {
            return now.add(allowed(s, delta));
        }
        b.raw = b.raw.add(delta); // предмет ещё не помечен: запомним, поправим в end()
        return newEmc;
    }

    public static void end() {
        Scope s = current();
        if (s == null) return;
        if (--s.depth > 0) return;
        SCOPE.remove(); // дальше setEmc проходит без вмешательства
        if (!s.learning) return;

        for (Map.Entry<IKnowledgeProvider, Baseline> e : s.baselines.entrySet()) {
            Baseline b = e.getValue();
            if (b.raw.signum() <= 0) continue;

            IKnowledgeProvider provider = e.getKey();
            BigInteger now = provider.getEmc();
            BigInteger remove = b.raw.subtract(allowed(s, b.raw));
            BigInteger target = now.subtract(remove).max(b.emc);
            if (target.compareTo(now) < 0) {
                provider.setEmc(target);
                if (b.owner != null) {
                    provider.syncEmc(b.owner); // если не компилируется - замените на provider.sync(b.owner)
                }
            }
        }
    }
}