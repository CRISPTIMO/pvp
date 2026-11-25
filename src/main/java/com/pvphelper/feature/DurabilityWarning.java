package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * Предупреждение о низкой прочности: следит за бронёй и предметами в руках;
 * когда прочность падает ниже порога — выводит предупреждение в actionbar
 * и проигрывает звук. Повторно по одному слоту не спамит (перевзводится,
 * когда предмет заменён/починен).
 */
public final class DurabilityWarning {

    public static boolean enabled = true;

    private static final EnumMap<EquipmentSlot, Boolean> warned = new EnumMap<>(EquipmentSlot.class);
    private static int cooldown = 0;

    private DurabilityWarning() {}

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!enabled || !PvpConfig.DURABILITY_WARN_ENABLED.get()) return;

        if (cooldown > 0) { cooldown--; }

        int threshold = PvpConfig.DURABILITY_WARN_THRESHOLD.get();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                warned.put(slot, false);
                continue;
            }
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            boolean low = remaining <= threshold;
            boolean already = warned.getOrDefault(slot, false);

            if (!low) {
                warned.put(slot, false); // перевзвод: предмет заменён/починен
                continue;
            }
            if (!already && cooldown == 0) {
                warn(mc, player, slot, stack, remaining);
                warned.put(slot, true);
                cooldown = 20; // не чаще раза в секунду между разными предметами
            }
        }
    }

    private static void warn(Minecraft mc, LocalPlayer player, EquipmentSlot slot,
                             ItemStack stack, int remaining) {
        player.displayClientMessage(Component.literal(
            "§c⚠ §f" + stack.getHoverName().getString()
            + " §7(" + slotName(slot) + ") §cпрочность: " + remaining), true);
        player.playSound(SoundEvents.ANVIL_LAND, 0.5f, 1.6f);
    }

    private static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "шлем";
            case CHEST -> "нагрудник";
            case LEGS -> "поножи";
            case FEET -> "ботинки";
            case MAINHAND -> "рука";
            case OFFHAND -> "левая рука";
            default -> slot.getName();
        };
    }
}
