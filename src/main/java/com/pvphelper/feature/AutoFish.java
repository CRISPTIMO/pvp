package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Авто-рыбалка: игрок закидывает удочку САМ (первый заброс ручной — так мод
 * понимает, что рыбачить действительно нужно), дальше мод ловит поклёвку,
 * подсекает и перезакидывает. Если смотать удочку вручную — мод снова ждёт
 * ручного заброса.
 * Три режима (переключаются Shift+ЛКМ с удочкой в руке):
 *  - BREAK_CURRENT — ловить, пока текущая удочка не сломается;
 *  - KEEP_ONE      — ловить, пока не останется 1 прочности (удочку не ломаем);
 *  - ALL_RODS      — перебирать все удочки в инвентаре, ломая их.
 */
public final class AutoFish {

    public static boolean enabled = false; // включается клавишей, чтобы не мешать обычной ловле

    public enum Mode {
        BREAK_CURRENT("до поломки удочки"),
        KEEP_ONE("до 1 прочности"),
        ALL_RODS("все удочки с ломанием");

        final String label;
        Mode(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static Mode mode = Mode.KEEP_ONE;

    // Детект поклёвки: резкое погружение поплавка вниз.
    private static final double BITE_VELOCITY = -0.08;
    private static final int MIN_CAST_TICKS = 25;   // защита от ложных срабатываний сразу после заброса
    private static final int RECAST_DELAY = 18;     // пауза после подсечки перед новым забросом

    private static int hookAge = 0;
    private static int lastHookId = -1;      // id текущего поплавка: новый поплавок -> сброс hookAge
    private static boolean awaitRecast = false; // мы подсекли/прервались и должны перезакинуть сами
    private static int recastCooldown = 0;
    private static boolean idle = false; // режим завершён (например, KEEP_ONE достиг предела)
    private static boolean sessionActive = false; // игрок уже забросил — рыбалка идёт (для AFK/смены удочки)

    private AutoFish() {}

    /** Переключение режима по Shift+ЛКМ (вызывается только когда в руке удочка). */
    public static void cycleMode(Minecraft mc) {
        Mode[] all = Mode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        idle = false;
        com.pvphelper.util.Sfx.mode();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("§6[Рыбалка] §fрежим: §e" + mode.label), true);
        }
    }

    /** Сброс состояния при включении фичи клавишей. */
    public static void reset() {
        hookAge = 0;
        lastHookId = -1;
        awaitRecast = false;
        recastCooldown = 0;
        idle = false;
        sessionActive = false;
    }

    public static void tick(Minecraft mc) {
        if (!enabled || !PvpConfig.AUTO_FISH_ENABLED.get()) return;
        LocalPlayer player = mc.player;
        // ВАЖНО: работаем и при открытом экране (чат/инвентарь/меню) —
        // подсечка и перезаброс идут пакетами и не требуют закрытого GUI.
        if (player == null || mc.level == null || mc.gameMode == null) return;

        // Пауза на время авто-еды: она временно забирает руку под еду. Если из-за
        // этого пропал поплавок — после еды надо перезакинуть (для AFK-рыбалки).
        if (AutoEat.isEating()) {
            if (sessionActive && player.fishing == null) awaitRecast = true;
            return;
        }

        if (recastCooldown > 0) { recastCooldown--; return; }

        boolean rodInHand = isRod(player.getMainHandItem());
        FishingHook hook = player.fishing;

        if (!rodInHand) {
            // Удочки нет в руке: сломалась, кончилась или игрок сам сменил предмет.
            if (sessionActive && !idle && mode == Mode.ALL_RODS) {
                // Режим «все удочки»: берём следующую (хотбар или инвентарь) и продолжаем.
                if (switchToNextRod(mc, player)) {
                    awaitRecast = true; // на след. тике забросим новой удочкой
                    return;
                }
                stop(mc, "удочки закончились");
                return;
            }
            // В остальных режимах — это ручная смена предмета: обрываем цикл.
            awaitRecast = false;
            sessionActive = false;
            return;
        }

        if (hook == null) {
            // Поплавка нет. Забрасываем ТОЛЬКО по нашему флагу (первый заброс — вручную).
            if (idle || !awaitRecast) return;
            if (!canCastByDurability(player)) {
                if (mode == Mode.ALL_RODS && switchToNextRod(mc, player)) {
                    return; // взяли новую удочку — забросим на следующем тике
                }
                stop(mc, "удочки закончились / достигнут предел прочности");
                return;
            }
            cast(mc, player);
            awaitRecast = false;
            sessionActive = true;
            return;
        }

        // Поплавок есть — рыбалка точно идёт.
        sessionActive = true;
        if (hook.getId() != lastHookId) {
            lastHookId = hook.getId();
            hookAge = 0;
            idle = false; // ручной заброс снимает "стоп"
        }

        hookAge++;
        double vy = hook.getDeltaMovement().y;
        boolean bite = hookAge > MIN_CAST_TICKS && vy < BITE_VELOCITY && hook.isInWater();
        if (bite) {
            reel(mc, player);           // подсечка
            awaitRecast = true;         // перезаброс теперь за нами
            recastCooldown = RECAST_DELAY;
            com.pvphelper.util.Sfx.bite();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§6[Рыбалка] §aпоклёвка!"), true);
            }
        }
    }

    /** Можно ли забрасывать текущей удочкой по правилам режима. */
    private static boolean canCastByDurability(LocalPlayer player) {
        ItemStack rod = player.getMainHandItem();
        if (!isRod(rod)) return false;
        int remaining = rod.getMaxDamage() - rod.getDamageValue();
        return switch (mode) {
            case KEEP_ONE -> remaining > 1;   // оставляем минимум 1 прочность
            default -> remaining > 0;         // BREAK_CURRENT / ALL_RODS — до нуля
        };
    }

    /** Ищет другую удочку и делает её выбранной (для ALL_RODS). */
    private static boolean switchToNextRod(Minecraft mc, LocalPlayer player) {
        // Сначала в хотбаре.
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (isRod(s) && (s.getMaxDamage() - s.getDamageValue()) > 0) {
                selectSlot(mc, i);
                return true;
            }
        }
        // Затем из основного инвентаря — перекладываем в текущий слот хотбара.
        // Свап кликами делаем только при закрытом GUI (иначе конфликт с открытым контейнером).
        if (mc.screen != null) return false;
        int cur = player.getInventory().getSelectedSlot();
        for (int i = 9; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (isRod(s) && (s.getMaxDamage() - s.getDamageValue()) > 0) {
                int menuSlot = i; // 9-35 совпадают с номерами меню InventoryMenu
                int containerId = player.inventoryMenu.containerId;
                // swap в текущий слот хотбара (hotbarButton = cur, режим SWAP)
                mc.gameMode.handleInventoryMouseClick(containerId, menuSlot, cur, ClickType.SWAP, player);
                return true;
            }
        }
        return false;
    }

    private static void cast(Minecraft mc, LocalPlayer player) {
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
    }

    private static void reel(Minecraft mc, LocalPlayer player) {
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
    }

    private static void stop(Minecraft mc, String reason) {
        idle = true;
        awaitRecast = false;
        sessionActive = false;
        com.pvphelper.util.Sfx.deny();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("§6[Рыбалка] §cстоп: §f" + reason), true);
        }
    }

    private static boolean isRod(ItemStack stack) {
        return stack.is(Items.FISHING_ROD);
    }

    private static void selectSlot(Minecraft mc, int slot) {
        LocalPlayer player = mc.player;
        if (player.getInventory().getSelectedSlot() == slot) return;
        player.getInventory().setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }
}
