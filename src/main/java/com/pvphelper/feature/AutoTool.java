package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Авто-инструмент: при начале ломания блока или ударе по существу
 * автоматически переключает выбранный слот хотбара на самый эффективный
 * предмет (по скорости добычи для блоков, по DPS — для боя, поэтому меч
 * выигрывает у топора). Если ничего лучше текущего предмета нет —
 * слот НЕ трогаем.
 */
public final class AutoTool {

    public static boolean enabled = true;

    // Минимальное преимущество, ради которого стоит переключать слот.
    private static final float MIN_GAIN = 0.01f;

    private AutoTool() {}

    /** Выбирает лучший инструмент под конкретный блок (только если он реально лучше). */
    public static void onMineBlock(Minecraft mc, BlockState state) {
        if (!ready(mc)) return;
        LocalPlayer player = mc.player;

        int current = player.getInventory().getSelectedSlot();
        int bestSlot = current;
        float bestScore = mineScore(player.getInventory().getItem(current), state);

        for (int slot = 0; slot < 9; slot++) {
            if (slot == current) continue;
            float score = mineScore(player.getInventory().getItem(slot), state);
            if (score > bestScore + MIN_GAIN) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        selectSlot(mc, bestSlot);
    }

    /** Выбирает предмет с наибольшим DPS ближнего боя (только если он лучше текущего). */
    public static void onAttackEntity(Minecraft mc) {
        if (!ready(mc)) return;
        LocalPlayer player = mc.player;

        int current = player.getInventory().getSelectedSlot();
        int bestSlot = current;
        double bestScore = meleeScore(player.getInventory().getItem(current));

        for (int slot = 0; slot < 9; slot++) {
            if (slot == current) continue;
            double score = meleeScore(player.getInventory().getItem(slot));
            if (score > bestScore + MIN_GAIN) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        selectSlot(mc, bestSlot);
    }

    private static boolean ready(Minecraft mc) {
        return enabled && PvpConfig.AUTO_TOOL_ENABLED.get()
            && mc.player != null && mc.screen == null;
    }

    private static float mineScore(ItemStack stack, BlockState state) {
        float speed = stack.isEmpty() ? 1.0f : stack.getDestroySpeed(state);
        // Небольшой бонус за «правильный» инструмент (влияет на дроп).
        if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
            speed += 0.5f;
        }
        return speed;
    }

    /**
     * DPS предмета: (базовый урон 1.0 + модификаторы ATTACK_DAMAGE) *
     * (базовая скорость 4.0 + модификаторы ATTACK_SPEED).
     * Меч (7 dmg * 1.6/т = 11.2) обгоняет топор (9 dmg * 0.9-1.0/т = ~9).
     */
    private static double meleeScore(ItemStack stack) {
        if (stack.isEmpty()) return 1.0 * 4.0;
        ItemAttributeModifiers mods = stack.getOrDefault(
            DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double damage = 1.0;   // базовый урон игрока
        double speed = 4.0;    // базовая скорость атаки игрока
        for (var entry : mods.modifiers()) {
            if (!entry.slot().test(EquipmentSlot.MAINHAND)) continue;
            if (entry.attribute().value() == Attributes.ATTACK_DAMAGE.value()) {
                damage += entry.modifier().amount();
            } else if (entry.attribute().value() == Attributes.ATTACK_SPEED.value()) {
                speed += entry.modifier().amount();
            }
        }
        return Math.max(0.1, damage) * Math.max(0.1, speed);
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
