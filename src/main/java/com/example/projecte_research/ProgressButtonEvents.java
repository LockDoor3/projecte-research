package com.example.projecte_research;

import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.gameObjs.gui.GUITransmutation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = projecte_research.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ProgressButtonEvents {
    private static final ResourceLocation TABS =
            new ResourceLocation("textures/gui/container/creative_inventory/tabs.png");

    private static final int PAD = 8;
    private static final int HEADER_H = 30;
    private static final int MIN_ROW_H = 20;
    private static final int SB_W = 8;
    private static final int HANDLE_H = 15;

    private static boolean open = false;
    private static boolean draggingBar = false;
    private static boolean searchActive = false;
    private static int scroll = 0;
    private static EditBox searchBox;

    // кэш списка (пересчитывается только при смене запроса / прогресса / открытии панели)
    private static List<Row> cachedRows = null;
    private static String cachedQuery = null;
    private static int cachedVersion = -1;

    private record Row(ItemStack stack, String name, int have, int required) {}

    // ---------- кнопка и строка поиска ----------

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GUITransmutation)) return;
        AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) event.getScreen();

        open = false;
        draggingBar = false;
        searchActive = false;
        scroll = 0;
        cachedRows = null;

        Font font = Minecraft.getInstance().font;

        searchBox = new EditBox(font,
                panelX(s) + PAD, panelY(s) + PAD, panelW(s) - PAD * 2, 16,
                Component.translatable("gui.projecte_research.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable("gui.projecte_research.search_hint").withStyle(ChatFormatting.GRAY));
        searchBox.setResponder(text -> scroll = 0);

        Component label = Component.translatable("gui.projecte_research.progress_button");
        int btnW = Math.max(110, font.width(label) + 20);
        int x = s.getGuiLeft();
        int y = Math.max(2, s.getGuiTop() - 22);
        event.addListener(Button.builder(label, b -> {
            open = !open;
            draggingBar = false;
            searchActive = false;
            searchBox.setFocused(false);
            scroll = 0;
            cachedRows = null;
        }).bounds(x, y, btnW, 20).build());
    }

    // ---------- геометрия (панель = размер окна стола) ----------

    private static int panelX(AbstractContainerScreen<?> s) { return s.getGuiLeft(); }
    private static int panelY(AbstractContainerScreen<?> s) { return s.getGuiTop(); }
    private static int panelW(AbstractContainerScreen<?> s) { return s.getXSize(); }
    private static int panelH(AbstractContainerScreen<?> s) { return s.getYSize(); }

    private static int areaH(AbstractContainerScreen<?> s) {
        return panelH(s) - HEADER_H - PAD - 2;
    }

    private static int visibleRows(AbstractContainerScreen<?> s) {
        return Math.max(1, areaH(s) / MIN_ROW_H);
    }

    private static int rowH(AbstractContainerScreen<?> s) {
        return Math.max(MIN_ROW_H, areaH(s) / visibleRows(s));
    }

    private static int trackX(AbstractContainerScreen<?> s) {
        return panelX(s) + panelW(s) - PAD - SB_W;
    }

    private static int trackY(AbstractContainerScreen<?> s) {
        return panelY(s) + HEADER_H;
    }

    private static int trackH(AbstractContainerScreen<?> s) {
        return visibleRows(s) * rowH(s);
    }

    private static boolean inPanel(AbstractContainerScreen<?> s, double mx, double my) {
        return mx >= panelX(s) && mx < panelX(s) + panelW(s)
                && my >= panelY(s) && my < panelY(s) + panelH(s);
    }

    private static boolean onSearchBox(double mx, double my) {
        return mx >= searchBox.getX() && mx < searchBox.getX() + searchBox.getWidth()
                && my >= searchBox.getY() && my < searchBox.getY() + searchBox.getHeight();
    }

    private static boolean ready(Object screen) {
        return open && searchBox != null && screen instanceof GUITransmutation;
    }

    private static void unfocusOthers(AbstractContainerScreen<?> s) {
        for (GuiEventListener child : s.children()) {
            if (child instanceof EditBox box && box != searchBox) {
                box.setFocused(false);
            }
        }
        s.setFocused(null);
    }

    // ---------- данные ----------

    /** Список с кэшем: пересчёт только при смене запроса или прогресса. */
    private static List<Row> rows() {
        String q = searchBox == null ? "" : searchBox.getValue();
        int version = ClientProgress.version();
        if (cachedRows == null || !q.equals(cachedQuery) || version != cachedVersion) {
            cachedRows = buildRows(q);
            cachedQuery = q;
            cachedVersion = version;
        }
        return cachedRows;
    }

    private static List<Row> buildRows(String queryText) {
        List<Row> rows = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        if (player == null) return rows;

        SearchQuery query = SearchQuery.parse(queryText);

        for (Map.Entry<String, Integer> e : ClientProgress.all().entrySet()) {
            int have = e.getValue();
            if (have <= 0) continue;

            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null) continue;
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) continue;

            int required = ClientProgress.required(item);
            if (have >= required) continue;

            ItemStack stack = new ItemStack(item);
            boolean learned = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)
                    .map(p -> p.hasKnowledge(stack))
                    .orElse(false);
            if (learned) continue;

            if (!query.matches(stack)) continue;

            rows.add(new Row(stack, stack.getHoverName().getString(), have, required));
        }
        rows.sort(Comparator.comparingInt(Row::have).reversed());
        return rows;
    }

    private static int maxScroll(AbstractContainerScreen<?> s, int rowCount) {
        return Math.max(0, rowCount - visibleRows(s));
    }

    private static void dragTo(AbstractContainerScreen<?> s, double my, int max) {
        if (max <= 0) return;
        double range = trackH(s) - 2 - HANDLE_H;
        double frac = (my - (trackY(s) + 1) - HANDLE_H / 2.0) / range;
        scroll = Mth.clamp((int) Math.round(frac * max), 0, max);
    }

    // ---------- рамка со скруглёнными углами ----------

    private static void drawFrame(GuiGraphics g, int x, int y, int w, int h) {
        final int BLACK = 0xFF000000;
        final int BODY = 0xFFC6C6C6;
        final int LIGHT = 0xFFFFFFFF;
        final int SHADOW = 0xFF555555;

        g.fill(x + 2, y, x + w - 2, y + 1, BLACK);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, BLACK);
        g.fill(x, y + 2, x + 1, y + h - 2, BLACK);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, BLACK);
        g.fill(x + 1, y + 1, x + 2, y + 2, BLACK);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, BLACK);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, BLACK);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, BLACK);

        g.fill(x + 2, y + 1, x + w - 2, y + h - 1, BODY);
        g.fill(x + 1, y + 2, x + w - 1, y + h - 2, BODY);

        g.fill(x + 2, y + 1, x + w - 3, y + 3, LIGHT);
        g.fill(x + 1, y + 2, x + 3, y + h - 3, LIGHT);

        g.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, SHADOW);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, SHADOW);
    }

    // ---------- отрисовка ----------

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!ready(event.getScreen())) return;
        AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) event.getScreen();

        searchBox.setFocused(searchActive);
        if (searchActive) {
            unfocusOthers(s);
        }

        GuiGraphics g = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int x = panelX(s);
        int y = panelY(s);

        List<Row> rows = rows();
        int visible = visibleRows(s);
        int rowH = rowH(s);
        int max = maxScroll(s, rows.size());
        scroll = Mth.clamp(scroll, 0, max);

        int tx = trackX(s);
        int ty = trackY(s);
        int th = trackH(s);

        g.pose().pushPose();
        g.pose().translate(0, 0, 500);

        drawFrame(g, x, y, panelW(s), panelH(s));

        searchBox.render(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());

        if (rows.isEmpty()) {
            boolean searching = !searchBox.getValue().isBlank();
            g.drawString(font,
                    Component.translatable(searching
                            ? "gui.projecte_research.nothing_found"
                            : "gui.projecte_research.nothing_yet"),
                    x + PAD, ty + 6, 0x404040, false);
        } else {

            int rowRight = tx - 4;
            int shown = Math.min(visible, rows.size() - scroll);
            for (int i = 0; i < shown; i++) {
                Row row = rows.get(scroll + i);
                int ry = ty + i * rowH;

                g.renderItem(row.stack(), x + PAD, ry + (rowH - 16) / 2);

                String counter = row.have() + "/" + row.required();
                int counterW = font.width(counter);
                int textY = ry + (rowH - 8) / 2;
                g.drawString(font, counter, rowRight - counterW, textY, 0x555555, false);

                int nameMaxW = rowRight - (x + PAD + 22) - counterW - 4;
                g.drawString(font,
                        Language.getInstance().getVisualOrder(font.substrByWidth(Component.literal(row.name()), nameMaxW)),
                        x + PAD + 22, textY, 0x404040, false);

                if (i < shown - 1) {
                    g.fill(x + PAD, ry + rowH - 1, rowRight, ry + rowH, 0x30000000);
                }
            }
        }

        // скроллбар
        g.fill(tx, ty, tx + SB_W, ty + th, 0xFFFFFFFF);
        g.fill(tx, ty, tx + SB_W - 1, ty + th - 1, 0xFF373737);
        g.fill(tx + 1, ty + 1, tx + SB_W - 1, ty + th - 1, 0xFF8B8B8B);

        int u = max > 0 ? 232 : 244;
        int hy = ty + 1 + (max > 0 ? (int) ((th - 2 - HANDLE_H) * (scroll / (float) max)) : 0);
        g.blit(TABS, tx + 1, hy, u, 0, 3, HANDLE_H);
        g.blit(TABS, tx + 4, hy, u + 9, 0, 3, HANDLE_H);

        g.pose().popPose();
    }

    // Пока панель открыта, подсказки скрытых под ней слотов не показываем
    @SubscribeEvent
    public static void onTooltip(RenderTooltipEvent.Pre event) {
        if (open && Minecraft.getInstance().screen instanceof GUITransmutation) {
            event.setCanceled(true);
        }
    }

    // ---------- мышь ----------

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!ready(event.getScreen())) return;
        AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) event.getScreen();
        double mx = event.getMouseX();
        double my = event.getMouseY();

        if (!inPanel(s, mx, my)) {
            searchActive = false;
            searchBox.setFocused(false);
            return;
        }
        event.setCanceled(true);

        if (onSearchBox(mx, my)) {
            searchActive = true;
            unfocusOthers(s);
            searchBox.setFocused(true);
            searchBox.mouseClicked(mx, my, event.getButton());
            searchBox.setFocused(true);
            return;
        }

        searchActive = false;
        searchBox.setFocused(false);

        int max = maxScroll(s, rows().size());
        int tx = trackX(s);
        int ty = trackY(s);
        if (event.getButton() == 0 && max > 0
                && mx >= tx && mx < tx + SB_W && my >= ty && my < ty + trackH(s)) {
            draggingBar = true;
            dragTo(s, my, max);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!ready(event.getScreen())) return;
        AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) event.getScreen();
        if (draggingBar) {
            dragTo(s, event.getMouseY(), maxScroll(s, rows().size()));
            event.setCanceled(true);
        } else if (inPanel(s, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!ready(event.getScreen())) return;
        AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) event.getScreen();
        if (draggingBar) {
            draggingBar = false;
            event.setCanceled(true);
        } else if (inPanel(s, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!ready(event.getScreen())) return;
        AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) event.getScreen();
        if (inPanel(s, event.getMouseX(), event.getMouseY())) {
            int max = maxScroll(s, rows().size());
            scroll = Mth.clamp(scroll - (int) Math.signum(event.getScrollDelta()), 0, max);
            event.setCanceled(true);
        }
    }

    // ---------- клавиатура ----------

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!ready(event.getScreen()) || !searchActive) return;

        if (event.getKeyCode() == 256) { // Esc: только снимаем фокус
            searchActive = false;
            searchBox.setFocused(false);
        } else {
            searchBox.setFocused(true);
            searchBox.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!ready(event.getScreen()) || !searchActive) return;
        searchBox.setFocused(true);
        searchBox.charTyped(event.getCodePoint(), event.getModifiers());
        event.setCanceled(true);
    }
}