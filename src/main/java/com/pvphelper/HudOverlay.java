package com.pvphelper;

import com.pvphelper.feature.AimAssist;
import com.pvphelper.feature.AutoEat;
import com.pvphelper.feature.AutoFarm;
import com.pvphelper.feature.AutoFish;
import com.pvphelper.feature.AutoRefill;
import com.pvphelper.feature.AutoTool;
import com.pvphelper.feature.AutoTotem;
import com.pvphelper.feature.CombatAssist;
import com.pvphelper.feature.MeleeAssist;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD-индикатор в левом верхнем углу: какие фичи сейчас активны и в каких
 * режимах (бой, рыбалка, пауза еды). Показывает только включённое.
 * Регистрируется слоем оверлея через {@link AddGuiOverlayLayersEvent}
 * (Forge 61: ForgeLayeredDraw вместо старых RenderGui*-событий).
 */
public final class HudOverlay {

    private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath("pvphelper", "hud");

    private static final int MARGIN = 4;
    private static final int PAD = 3;
    private static final int BG_COLOR = 0x59000000;   // полупрозрачная подложка
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static volatile boolean layerAdded = false;

    private HudOverlay() {}

    /** Регистрация слоя (событие AddGuiOverlayLayersEvent). */
    public static void onAddLayers(AddGuiOverlayLayersEvent event) {
        if (layerAdded) return; // повторный вызов из ensureRegistered()
        layerAdded = true;
        event.getLayeredDraw().add(LAYER_ID, HudOverlay::render);
        PvpHelper.LOGGER.info("PvP Helper: HUD-слой зарегистрирован штатно (через событие)");
    }

    /**
     * Страховка от гонки на старте: Gui (и его дерево слоёв) создаётся в
     * конструкторе Minecraft, а моды грузятся асинхронно — событие
     * AddGuiOverlayLayersEvent может уйти ДО того, как мы подписались.
     * Тогда на первом тике доустанавливаем слой в приватный корень
     * ForgeLayeredDraw рефлексией и перезапекаем список слоёв.
     */
    public static void ensureRegistered() {
        if (layerAdded) return;
        layerAdded = true;
        try {
            var field = net.minecraftforge.client.gui.overlay.ForgeLayeredDraw.class
                .getDeclaredField("instance");
            field.setAccessible(true);
            var root = (net.minecraftforge.client.gui.overlay.ForgeLayeredDraw) field.get(null);
            root.add(LAYER_ID, HudOverlay::render);
            root.resolveLayers(); // перезапекает bakedLayers (наш слой попадает в рендер)
            PvpHelper.LOGGER.info("PvP Helper: HUD-слой добавлен фолбэком (событие слоёв прошло до регистрации мода)");
        } catch (ReflectiveOperationException e) {
            PvpHelper.LOGGER.error("PvP Helper: не удалось добавить HUD-слой фолбэком", e);
        }
    }

    // Момент последней отрисовки через слой. Если слой не рисовал в текущем
    // кадре (его выкинул чужой оптимизатор HUD-слоёв), запасной путь берёт HUD
    // на себя. Так рисуем ровно один раз за кадр независимо от того, кто сработал.
    private static volatile long lastLayerRenderNanos = 0;
    private static boolean loggedFallback = false;

    /** Список координат игроков (тумблер Numpad .). */
    public static boolean playerEsp = false;
    private static final int ESP_MAX = 10; // не засорять экран — топ ближайших

    private static void render(GuiGraphics g, DeltaTracker delta) {
        lastLayerRenderNanos = System.nanoTime();
        drawHud(g);
    }

    /**
     * Запасной рендер через CustomizeGuiOverlayEvent.Chat (стреляет каждый кадр,
     * его не трогают оптимизаторы HUD-слоёв): рисуем, если слой в этом кадре молчал.
     */
    public static void onChatOverlay(net.minecraftforge.client.event.CustomizeGuiOverlayEvent.Chat event) {
        // 50 мс ≈ кадр при 20+ FPS: если слой не рисовал так долго — он не работает.
        if (System.nanoTime() - lastLayerRenderNanos > 50_000_000L) {
            if (!loggedFallback) {
                loggedFallback = true;
                PvpHelper.LOGGER.info("PvP Helper: HUD рисуется запасным путём (слой перехвачен другим модом)");
            }
            drawHud(event.getGuiGraphics());
        }
    }

    private static void drawHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        if (!PvpConfig.HUD_ENABLED.get()) return;

        List<String> lines = buildLines();
        Font font = mc.font;

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int lineH = font.lineHeight + 2;
        int height = lines.size() * lineH;

        // Правый верхний угол (левый занят миникартой пользователя).
        int x = g.guiWidth() - MARGIN - width;
        g.fill(x - PAD, MARGIN - PAD, x + width + PAD, MARGIN + height + PAD, BG_COLOR);

        int y = MARGIN;
        for (String line : lines) {
            g.drawString(font, line, x, y, TEXT_COLOR, true);
            y += lineH;
        }
    }

    private static List<String> buildLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§c§lШТРАФОСТАН §f§lTOOLS");

        if (AutoTotem.enabled && PvpConfig.AUTO_TOTEM_ENABLED.get()) {
            lines.add("§aТотем");
        }
        if (AutoTool.enabled && PvpConfig.AUTO_TOOL_ENABLED.get()) {
            lines.add("§aИнструмент");
        }
        if (AimAssist.enabled && PvpConfig.AIM_ASSIST_ENABLED.get()) {
            lines.add("§aПрицел");
        }
        if (AimAssist.throwAimEnabled && AimAssist.enabled && PvpConfig.AIM_ASSIST_ENABLED.get()) {
            lines.add("§aМетательные");
        }
        if (AutoFish.enabled && PvpConfig.AUTO_FISH_ENABLED.get()) {
            lines.add("§aРыбалка §7(" + AutoFish.mode.label() + ")");
        }
        if (AutoFarm.enabled && PvpConfig.AUTO_FARM_ENABLED.get()) {
            lines.add("§aФерма");
        }
        if (MeleeAssist.aimEnabled && PvpConfig.MELEE_AIM_ENABLED.get()) {
            lines.add("§aНаводка");
        }
        if (CombatAssist.enabled) {
            lines.add("§aБой §7(" + CombatAssist.mode.label() + ")");
        }
        if (AutoEat.enabled && PvpConfig.AUTO_EAT_ENABLED.get()) {
            lines.add(AutoEat.inCombatPause() ? "§eЕда §c(пауза: бой)" : "§aЕда");
        }
        if (AutoRefill.enabled && PvpConfig.AUTO_REFILL_ENABLED.get()) {
            lines.add("§aРефилл");
        }
        if (com.pvphelper.feature.BridgeAssist.enabled && PvpConfig.BRIDGE_ENABLED.get()) {
            lines.add("§aМост");
        }
        if (com.pvphelper.feature.AutoWaterBucket.enabled && PvpConfig.AUTO_WATER_ENABLED.get()) {
            lines.add("§aВода");
        }
        if (com.pvphelper.feature.CartBomb.isRunning()) {
            lines.add("§e§lТНТ-комбо...");
        }

        // Список координат видимых игроков по никам.
        if (playerEsp && PvpConfig.PLAYER_ESP_ENABLED.get()) {
            appendPlayerCoords(lines);
        }
        return lines;
    }

    /** Добавляет строки «ник: x y z (дист)» для видимых игроков, ближайшие сверху. */
    private static void appendPlayerCoords(List<String> lines) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var others = new ArrayList<net.minecraft.world.entity.player.Player>();
        var visibleNames = new java.util.HashSet<String>();
        for (var p : mc.level.players()) {
            if (p != mc.player) { others.add(p); visibleNames.add(p.getScoreboardName()); }
        }

        // Тиммейты из релея, которых сейчас не видно рядом (координаты «где угодно»).
        var relay = new ArrayList<com.pvphelper.feature.TeamRelay.Remote>();
        for (var r : com.pvphelper.feature.TeamRelay.players()) {
            if (!visibleNames.contains(r.name())) relay.add(r);
        }

        if (others.isEmpty() && relay.isEmpty()) {
            lines.add("§bИгроков не обнаружено");
            return;
        }
        // Сортировка: сначала свои, затем по дистанции.
        others.sort(java.util.Comparator
            .comparing((net.minecraft.world.entity.player.Player p) ->
                com.pvphelper.feature.Friends.isFriend(p.getScoreboardName()) ? 0 : 1)
            .thenComparingDouble(p -> p.distanceToSqr(mc.player)));

        lines.add("§b§lИгроки:");
        int shown = 0;
        for (var p : others) {
            if (shown++ >= ESP_MAX) {
                lines.add("§7  … ещё " + (others.size() - ESP_MAX));
                break;
            }
            int dist = (int) Math.sqrt(p.distanceToSqr(mc.player));
            boolean friend = com.pvphelper.feature.Friends.isFriend(p.getScoreboardName());
            String nameColor = friend ? "§a" : "§c"; // свои — зелёные, прочие — красные
            lines.add(String.format("%s%s §7%d %d %d §8(%dм)",
                nameColor, p.getScoreboardName(),
                p.getBlockX(), p.getBlockY(), p.getBlockZ(), dist));
        }

        // Удалённые тиммейты из релея — всегда зелёные, с пометкой.
        String myDim = mc.player.level().dimension().identifier().toString();
        for (var r : relay) {
            String distStr;
            if (myDim.equals(r.dim())) {
                double dx = r.x() - mc.player.getX(), dz = r.z() - mc.player.getZ();
                distStr = (int) Math.sqrt(dx * dx + dz * dz) + "м";
            } else {
                distStr = shortDim(r.dim());
            }
            lines.add(String.format("§a%s §7%d %d %d §8(%s) §8⇄",
                r.name(), (int) r.x(), (int) r.y(), (int) r.z(), distStr));
        }
    }

    /** Короткое имя измерения для метки релея. */
    private static String shortDim(String dim) {
        if (dim.contains("nether")) return "Ад";
        if (dim.contains("end")) return "Энд";
        if (dim.contains("overworld")) return "Овер";
        return dim;
    }
}
