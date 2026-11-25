package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * Авто-еда: когда сытость падает ниже порога, автоматически берёт еду
 * (сначала из хотбара, иначе достаёт из инвентаря свапом), «зажимает»
 * использование до конца поедания и возвращает всё на место.
 * Опасную/ценную еду (гнильё, паучий глаз, золотые яблоки, плод хоруса)
 * не ест.
 */
public final class AutoEat {

    public static boolean enabled = false;

    private static final Set<Item> BLACKLIST = Set.of(
        Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
        Items.PUFFERFISH, Items.CHICKEN, Items.CHORUS_FRUIT,
        Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.SUSPICIOUS_STEW);

    private static boolean eating = false;
    private static int restoreSlot = -1;   // хотбар-слот для возврата выбора (-1 если не меняли)
    private static int swapInvSlot = -1;    // инвент-слот, откуда свапнули еду (-1 если из хотбара)

    // Пауза в бою: пока таймер > 0, авто-еда не стартует (и прерывается),
    // чтобы поедание не замедляло игрока в ПвП. Исключение — критический голод.
    private static int combatTicks = 0;

    // Гэппл-режим: в бою при низком ХП сжираем золотое яблоко, боевая пауза
    // на него НЕ действует (наоборот — он для боя и нужен).
    private static boolean gappleMode = false;

    // Добор до полной шкалы: старт по порогу голода, но едим до 20 (не до порога+2).
    private static boolean fillingUp = false;
    private static final int FULL_FOOD = 20;

    private AutoEat() {}

    /** Зовётся, когда игрок бьёт (или получает удар) — включает боевую паузу. */
    public static void noteCombat() {
        combatTicks = PvpConfig.AUTO_EAT_COMBAT_PAUSE.get() * 20;
    }

    // Рядом чужой игрок (не из команды) — тоже считаем боем: пауза не спадёт
    // во время дуэли, даже если 10 сек не было ударов.
    private static final double COMBAT_PLAYER_RANGE = 14.0;

    /** Сейчас идёт поедание (рука занята едой) — например, чтобы авто-рыбалка ждала. */
    public static boolean isEating() {
        return eating;
    }

    /** Для HUD: авто-еда сейчас на боевой паузе. */
    public static boolean inCombatPause() {
        return combatTicks > 0 || enemyNearby(Minecraft.getInstance());
    }

    /** Публичный признак «в бою» — общий для мода (еда, блокировка копки мечом и т.п.). */
    public static boolean isInCombat() {
        return isInCombat(Minecraft.getInstance());
    }

    /** «В бою»: недавний удар ИЛИ рядом чужой игрок. */
    private static boolean isInCombat(Minecraft mc) {
        return combatTicks > 0 || enemyNearby(mc);
    }

    /** Есть ли рядом игрок не из команды (Friends) в пределах боевого радиуса. */
    private static boolean enemyNearby(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        double r2 = COMBAT_PLAYER_RANGE * COMBAT_PLAYER_RANGE;
        for (var p : mc.level.players()) {
            if (p == mc.player || !p.isAlive()) continue;
            if (Friends.isFriend(p.getScoreboardName())) continue;
            if (p.distanceToSqr(mc.player) <= r2) return true;
        }
        return false;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.screen != null) { finish(mc); return; }
        if (!enabled || !PvpConfig.AUTO_EAT_ENABLED.get()) { finish(mc); return; }

        // Нас ударили — тоже бой.
        if (player.hurtTime > 0) noteCombat();

        int threshold = PvpConfig.AUTO_EAT_HUNGER.get();
        int food = player.getFoodData().getFoodLevel();

        // --- Гэппл при низком ХП в бою (важнее любой другой логики) ---
        if (eating && gappleMode) {
            ItemStack held = player.getMainHandItem();
            // Съели (появилась жёлтая броня-абсорбция) или гэппл пропал из руки — стоп.
            if (player.getAbsorptionAmount() > 0.5f || !isGapple(held)) {
                finish(mc);
            } else {
                keyUse(mc, true);
            }
            return;
        }
        boolean inCombat = isInCombat(mc);
        if (combatTicks > 0) combatTicks--; // таймер тикает всегда

        int gappleHp = PvpConfig.AUTO_EAT_GAPPLE_HP.get();
        if (!eating && inCombat && gappleHp > 0
                && player.getHealth() <= gappleHp
                && player.getAbsorptionAmount() <= 0.5f) {
            if (startGapple(mc, player)) return;
        }

        if (inCombat) {
            // В бою обычную авто-еду отключаем ПОЛНОСТЬЮ (гэппл выше — он для боя).
            if (eating) {
                finish(mc);
                com.pvphelper.util.Sfx.mode();
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§6[Еда] §cпауза: бой"), true);
            }
            return;
        }

        if (eating) {
            ItemStack held = player.getMainHandItem();
            if (food >= FULL_FOOD) {          // шкала полная — стоп
                fillingUp = false;
                finish(mc);
                return;
            }
            if (isEdible(held)) {
                keyUse(mc, true);             // держим ПКМ — продолжаем есть
                return;
            }
            // Текущий стак еды кончился, а шкала ещё не полная — доедаем следующим.
            finish(mc);
            // не return: ниже в этом же тике выберем новую еду
        }

        // Старт, когда проголодались (≤ порога); дальше — добор до полной шкалы.
        boolean wantEat = fillingUp ? (food < FULL_FOOD) : (food <= threshold);
        if (!wantEat) { fillingUp = false; return; }

        // 1) Еда в хотбаре — просто выбираем её.
        int hotbar = findFoodHotbar(player);
        if (hotbar >= 0) {
            restoreSlot = player.getInventory().getSelectedSlot();
            swapInvSlot = -1;
            selectSlot(mc, hotbar);
            fillingUp = true;
            startEating(mc);
            return;
        }

        // 2) Еда только в инвентаре — свапаем в текущий слот.
        int inv = findFoodInventory(player);
        if (inv >= 0) {
            int cur = player.getInventory().getSelectedSlot();
            int containerId = player.inventoryMenu.containerId;
            mc.gameMode.handleInventoryMouseClick(containerId, inv, cur, ClickType.SWAP, player);
            swapInvSlot = inv;
            restoreSlot = -1;
            fillingUp = true;
            startEating(mc);
            return;
        }

        // Еды не нашли — режим добора сбрасываем.
        fillingUp = false;
    }

    private static void startEating(Minecraft mc) {
        eating = true;
        keyUse(mc, true);
    }

    /** Берёт золотое яблоко (хотбар или инвентарь свапом) и начинает есть. */
    private static boolean startGapple(Minecraft mc, LocalPlayer player) {
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            if (isGapple(player.getInventory().getItem(i))) { slot = i; break; }
        }
        if (slot < 0) return false;

        if (slot < 9) {
            restoreSlot = player.getInventory().getSelectedSlot();
            swapInvSlot = -1;
            selectSlot(mc, slot);
        } else {
            int cur = player.getInventory().getSelectedSlot();
            mc.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId, slot, cur, ClickType.SWAP, player);
            swapInvSlot = slot;
            restoreSlot = -1;
        }
        gappleMode = true;
        startEating(mc);
        player.displayClientMessage(
            net.minecraft.network.chat.Component.literal("§6[Еда] §eгэппл — мало ХП!"), true);
        return true;
    }

    private static boolean isGapple(ItemStack s) {
        return s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    private static void finish(Minecraft mc) {
        if (!eating) return;
        keyUse(mc, false);
        LocalPlayer player = mc.player;
        if (player != null && mc.gameMode != null && swapInvSlot >= 0) {
            // Вернуть остаток еды в инвентарь, а прежний предмет — в руку.
            int cur = player.getInventory().getSelectedSlot();
            int containerId = player.inventoryMenu.containerId;
            mc.gameMode.handleInventoryMouseClick(containerId, swapInvSlot, cur, ClickType.SWAP, player);
        } else if (restoreSlot >= 0) {
            selectSlot(mc, restoreSlot);
        }
        eating = false;
        gappleMode = false;
        restoreSlot = -1;
        swapInvSlot = -1;
    }

    private static int findFoodHotbar(LocalPlayer player) {
        return bestFood(player, 0, 9);
    }

    private static int findFoodInventory(LocalPlayer player) {
        return bestFood(player, 9, 36);
    }

    /** Слот с самой питательной подходящей едой в диапазоне [from, to), или -1. */
    private static int bestFood(LocalPlayer player, int from, int to) {
        int best = -1;
        int bestNutrition = -1;
        for (int i = from; i < to; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!isEdible(s) || BLACKLIST.contains(s.getItem())) continue;
            var food = s.get(DataComponents.FOOD);
            int nutrition = food != null ? food.nutrition() : 0;
            if (nutrition > bestNutrition) {
                bestNutrition = nutrition;
                best = i;
            }
        }
        return best;
    }

    private static boolean isEdible(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    private static void keyUse(Minecraft mc, boolean down) {
        mc.options.keyUse.setDown(down);
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
