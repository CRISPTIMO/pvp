package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Боевой ассист для ближнего боя. Numpad 7 — вкл/выкл, Numpad 0 — смена режима:
 *  - CRIT         — «честные» криты: бьём только когда игрок реально в состоянии
 *                   крита (падает вниз). Пакеты не шлём — анти-читам придраться не к чему;
 *  - PACKET_CRIT  — форс-крит стоя на земле через packet-hop. Палится анти-читами!
 *  - FULL         — просто авто-удар при полной зарядке шкалы.
 *
 * Во ВСЕХ режимах: пока зажата ЛКМ и перекрестье на существе — авто-удар ровно
 * при полной зарядке шкалы (клавишу можно держать, кликать не нужно).
 * Клики при недозаряженной шкале отменяются, чтобы не сбивать заряд слабым ударом.
 */
public final class CombatAssist {

    public static boolean enabled = false;

    public enum Mode {
        CRIT("только криты"),
        PACKET_CRIT("крит-пакеты"),
        FULL("полный заряд");

        final String label;
        Mode(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static Mode mode = Mode.CRIT;

    private CombatAssist() {}

    public static void toggle(Minecraft mc) {
        enabled = !enabled;
        if (enabled) com.pvphelper.util.Sfx.toggleOn(); else com.pvphelper.util.Sfx.toggleOff();
        if (mc.player != null) {
            String state = enabled ? "§aВКЛ" : "§cВЫКЛ";
            mc.player.displayClientMessage(
                Component.literal("§6[Бой] §f" + state + " §7(режим: " + mode.label() + ")"), true);
        }
    }

    public static void cycleMode(Minecraft mc) {
        Mode[] all = Mode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        com.pvphelper.util.Sfx.mode();
        if (mc.player != null) {
            String hint = enabled ? "" : " §8(ассист выключен — Numpad 7)";
            mc.player.displayClientMessage(
                Component.literal("§6[Бой] §fрежим: §e" + mode.label() + hint), true);
        }
    }

    /** Разрешён ли текущий режим конфигом. */
    private static boolean modeAllowed() {
        return switch (mode) {
            case CRIT -> true;
            case PACKET_CRIT -> PvpConfig.AUTO_CRIT_ENABLED.get();
            case FULL -> PvpConfig.AUTO_ATTACK_ENABLED.get();
        };
    }

    /** Тик: во всех режимах — авто-удар по цели при зажатой ЛКМ и полной шкале. */
    public static void tick(Minecraft mc) {
        if (!enabled || !modeAllowed()) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.screen != null) return;

        // Бьём только пока зажата ЛКМ и перекрестье на существе (значит, стены нет).
        if (!mc.options.keyAttack.isDown()) return;
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) return;

        // Ждём полной зарядки шкалы атаки — во всех режимах.
        if (player.getAttackStrengthScale(0.0f) < 1.0f) return;

        // Режимные условия.
        if (mode == Mode.CRIT && !isCritReady(player)) return;
        // PACKET_CRIT: packet-hop нужен только стоя на земле. В прыжке крит и так
        // проходит, в воде — обычный удар. Удар не пропускаем НИКОГДА, лишь
        // добавляем хоп, когда он уместен.
        if (mode == Mode.PACKET_CRIT && canPacketCrit(player)) {
            sendCritHop(player);
        }

        Entity target = ((EntityHitResult) mc.hitResult).getEntity();
        // Своих (у кого мод / в списке команды) авто-атака не бьёт.
        if (target instanceof net.minecraft.world.entity.player.Player tp
                && Friends.isFriend(tp.getScoreboardName())) return;
        mc.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        player.resetAttackStrengthTicker();
    }

    /**
     * Клавиша атаки нажата (ДО пакета удара). Возврат true = отменить удар.
     * Отменяем слабые (недозаряженные) и некритовые (в режиме CRIT) клики —
     * именно здесь, потому что отмена AttackEntityEvent НЕ останавливает
     * пакет атаки, который уже ушёл на сервер.
     */
    public static boolean onAttackPressed(Minecraft mc) {
        if (!enabled || !modeAllowed()) return false;
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null || mc.screen != null) return false;

        // Целимся не в существо (блок/воздух) — не вмешиваемся.
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY) return false;

        // Шкала не заряжена — глушим клик, чтобы не сбивать заряд слабым ударом.
        if (player.getAttackStrengthScale(0.0f) < 1.0f) return true;

        return switch (mode) {
            case CRIT -> !isCritReady(player); // некритовый удар — отменяем
            case PACKET_CRIT -> {
                if (canPacketCrit(player)) sendCritHop(player);
                yield false; // сам удар пропускаем — сервер засчитает крит
            }
            case FULL -> false;
        };
    }

    /** Можно ли слать packet-hop прямо сейчас. */
    private static boolean canPacketCrit(LocalPlayer player) {
        return player.onGround()
            && !player.isInWater()
            && !player.onClimbable()
            && player.getVehicle() == null;
    }

    /** Packet-hop: сервер видит "подпрыгнул и падает" — удар засчитывается критом. */
    private static void sendCritHop(LocalPlayer player) {
        var conn = player.connection;
        if (conn == null) return;

        // Спринт на сервере отключает крит — гасим пакетом.
        if (player.isSprinting()) {
            conn.send(new ServerboundPlayerCommandPacket(
                player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }

        double x = player.getX(), y = player.getY(), z = player.getZ();
        // Конструктор Pos(x, y, z, onGround, horizontalCollision) в 1.21.11.
        conn.send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0625, z, false, false));
        conn.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, false));
        conn.send(new ServerboundMovePlayerPacket.Pos(x, y + 1.1e-5, z, false, false));
        conn.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, false));
    }

    /**
     * Условия критического удара (приближение к ванильной логике Player.attack):
     * падение вниз, не на земле/лестнице, не в воде, без слепоты, не верхом,
     * не в спринте.
     */
    private static boolean isCritReady(LocalPlayer p) {
        return p.fallDistance > 0.0f
            && !p.onGround()
            && !p.onClimbable()
            && !p.isInWater()
            && !p.hasEffect(MobEffects.BLINDNESS)
            && p.getVehicle() == null
            && !p.isSprinting();
    }
}
