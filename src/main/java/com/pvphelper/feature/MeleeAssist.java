package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import com.pvphelper.util.Los;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Помощь в ближнем бою: пока зажата атака, доводит прицел на цель и держит её,
 * пока она рядом и жива. Когда цель ушла/умерла — сразу перезахватывает
 * БЛИЖАЙШЕГО врага в конусе. Своих (у кого мод / в списке команды) не трогает.
 */
public final class MeleeAssist {

    /** Наводка на цель в ближнем бою. */
    public static boolean aimEnabled = false;

    private static final double FOV_DEG = 40.0;       // конус захвата новой цели
    private static final double KEEP_FOV_DEG = 65.0;  // конус удержания уже захваченной (шире)

    private static int lockedId = -1;

    private MeleeAssist() {}

    public static void tick(Minecraft mc) {
        if (!aimEnabled || !PvpConfig.MELEE_AIM_ENABLED.get()) { lockedId = -1; return; }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) { lockedId = -1; return; }

        // Наводимся только когда игрок реально атакует (зажата ЛКМ).
        if (!mc.options.keyAttack.isDown()) { lockedId = -1; return; }

        Vec3 eye = player.getEyePosition(1.0f);

        // Держим захваченную цель, пока она валидна; иначе берём ближайшую.
        Entity target = keepLocked(mc, player, eye);
        if (target == null) {
            target = acquireNearest(mc, player, eye);
            lockedId = target != null ? target.getId() : -1;
        }
        if (target == null) return;

        // Целимся в корпус (выше таза); если цель частично укрыта — в видимую часть.
        Vec3 aimPoint = Los.visibleAimPoint(mc.level, player, eye, target);
        if (aimPoint == null) return;

        double dx = aimPoint.x - eye.x;
        double dy = aimPoint.y - eye.y;
        double dz = aimPoint.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        // Плавная доводка (общая с луком) — без рывка на цель.
        AimAssist.smoothAim(player, yaw, pitch);
    }

    /** Уже захваченная цель — держим, пока жива, в дальности и в широком конусе. */
    private static Entity keepLocked(Minecraft mc, LocalPlayer player, Vec3 eye) {
        if (lockedId < 0) return null;
        Entity e = mc.level.getEntity(lockedId);
        if (!valid(mc, player, eye, e, KEEP_FOV_DEG)) { lockedId = -1; return null; }
        return e;
    }

    /** Новая цель: БЛИЖАЙШИЙ подходящий враг в узком конусе взгляда. */
    private static Entity acquireNearest(Minecraft mc, LocalPlayer player, Vec3 eye) {
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!valid(mc, player, eye, e, FOV_DEG)) continue;
            double d = e.distanceToSqr(player);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = e;
            }
        }
        return best;
    }

    /** Общая проверка цели: живая, не свой, в дальности, в конусе, видна. */
    private static boolean valid(Minecraft mc, LocalPlayer player, Vec3 eye, Entity e, double fovDeg) {
        if (e == null || e == player || !(e instanceof LivingEntity living) || !living.isAlive()) return false;
        if (e instanceof net.minecraft.world.entity.decoration.ArmorStand) return false;
        boolean isPlayer = e instanceof Player;
        if (!isPlayer && !PvpConfig.MELEE_TARGET_MOBS.get()) return false;
        // Своих не бьём (список команды / у кого мод).
        if (isPlayer && Friends.isFriend(((Player) e).getScoreboardName())) return false;

        Vec3 point = e.position().add(0, e.getBbHeight() * 0.65, 0);
        Vec3 to = point.subtract(eye);
        double dist = to.length();
        if (dist > PvpConfig.MELEE_RANGE.get() || dist < 1.0e-3) return false;

        double angle = Math.toDegrees(Math.acos(Math.min(1.0, player.getViewVector(1.0f).dot(to) / dist)));
        if (angle > fovDeg) return false;

        return Los.visibleAimPoint(mc.level, player, eye, e) != null;
    }
}
