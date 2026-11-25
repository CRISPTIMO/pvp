package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import com.pvphelper.util.BallisticSolver;
import com.pvphelper.util.BallisticSolver.ProjectileType;
import com.pvphelper.util.Los;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Помощь в прицеливании: определяет, каким снарядом целится игрок
 * (лук / снежок / яйцо / жемчуг), находит цель В КОНУСЕ ВЗГЛЯДА,
 * считает баллистику и плавно доводит прицел.
 *
 * Детали:
 *  - траектория всегда считается на ПОЛНУЮ скорость снаряда — прицел не
 *    «гуляет» вверх-вниз по мере натяга тетивы (палевно и дёргает);
 *  - если цель видна частично (торчит голова из-за укрытия) — целимся
 *    в видимую часть, а не отказываемся от цели;
 *  - цель захватывается на время прицеливания — не перескакивает.
 */
public final class AimAssist {

    public static boolean enabled = true;
    /** Наводка метательных: переключается клавишей V (не удержание). */
    public static boolean throwAimEnabled = false;

    // Конус захвата цели: только то, что рядом с перекрестьем.
    private static final double LOCK_FOV_DEG = 30.0;
    // Гистерезис: захваченную цель держим в чуть более широком конусе.
    private static final double KEEP_FOV_DEG = 50.0;
    // Дальность для лука — фактически без ограничения (лимитирует прорисовка).
    private static final double BOW_RANGE = 128.0;
    // Доля пути до цели, проходимая прицелом за тик: выше — быстрее доводка.
    private static final float SMOOTH_FACTOR = 0.55f;
    // Ближе этого угла (градусы) доводим мгновенно — убирает «дрожь» у цели.
    private static final float SNAP_EPSILON = 0.4f;

    private static int lockedTargetId = -1;

    // История позиций захваченной цели для сглаживания скорости (упреждение).
    // Один тик разницы слишком шумит на стрейфах — усредняем по нескольким.
    private static final int VEL_SAMPLES = 4;
    private static final java.util.ArrayDeque<Vec3> posHistory = new java.util.ArrayDeque<>();
    private static int historyTargetId = -1;

    private AimAssist() {}

    /**
     * Сглаженная скорость цели (бл/тик) по истории позиций. Вертикаль приглушаем:
     * игрок на земле не летит вверх стабильно, а экстраполяция y на десятки тиков
     * увела бы прицел в небо. Для летящих/в воздухе — вертикаль не режем.
     */
    private static Vec3 smoothedVelocity(Entity target) {
        if (target.getId() != historyTargetId) {
            historyTargetId = target.getId();
            posHistory.clear();
        }
        posHistory.addLast(target.position());
        while (posHistory.size() > VEL_SAMPLES + 1) posHistory.removeFirst();

        if (posHistory.size() < 2) {
            return new Vec3(target.getX() - target.xOld, 0, target.getZ() - target.zOld);
        }
        Vec3 newest = posHistory.peekLast();
        Vec3 oldest = posHistory.peekFirst();
        int span = posHistory.size() - 1;
        Vec3 v = newest.subtract(oldest).scale(1.0 / span);

        boolean airborne = !target.onGround();
        double vy = airborne ? v.y : 0.0; // на земле вертикаль в упреждение не берём
        return new Vec3(v.x, vy, v.z);
    }

    public static void tick(Minecraft mc) {
        if (!enabled || !PvpConfig.AIM_ASSIST_ENABLED.get()) { lockedTargetId = -1; return; }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) { lockedTargetId = -1; return; }

        // Пока идёт ТНТ-комбо, луком управляет CartBomb — не перехватываем прицел.
        if (CartBomb.isRunning()) { lockedTargetId = -1; return; }

        ProjectileType type = resolveProjectile(player);
        if (type == null) { lockedTargetId = -1; return; }

        Vec3 eye = player.getEyePosition(1.0f);
        double range = (type == ProjectileType.ARROW) ? BOW_RANGE : PvpConfig.AIM_MAX_RANGE.get();

        Entity target = keepLockedTarget(mc, player, eye, range);
        if (target == null) {
            target = findTarget(mc, player, eye, range);
            lockedTargetId = target != null ? target.getId() : -1;
        }
        if (target == null) return;

        // Целимся в видимую часть цели (грудь → голова → ноги).
        Vec3 aimPoint = Los.visibleAimPoint(mc.level, player, eye, target);
        if (aimPoint == null) return;
        double aimHeight = aimPoint.y - target.getY();

        Vec3 vel = smoothedVelocity(target);
        BallisticSolver.Solution sol =
            BallisticSolver.solve(eye, target, type, type.speed, aimHeight, vel);
        if (sol == null) return;

        smoothAim(player, sol.yaw, sol.pitch);
    }

    /** Плавная доводка: часть пути за тик, вплотную к цели — мгновенно (без дрожи). */
    static void smoothAim(LocalPlayer player, float targetYaw, float targetPitch) {
        float dYaw = Mth.wrapDegrees(targetYaw - player.getYRot());
        float dPitch = clampPitch(targetPitch) - player.getXRot();

        if (Math.abs(dYaw) < SNAP_EPSILON && Math.abs(dPitch) < SNAP_EPSILON) {
            player.setYRot(targetYaw);
            player.setXRot(clampPitch(targetPitch));
            return;
        }
        player.setYRot(player.getYRot() + dYaw * SMOOTH_FACTOR);
        player.setXRot(clampPitch(player.getXRot() + dPitch * SMOOTH_FACTOR));
    }

    /** Какой снаряд активен прямо сейчас, или null, если наводка не нужна. */
    private static ProjectileType resolveProjectile(LocalPlayer player) {
        // Лук: наводим, пока игрок натягивает тетиву.
        if (player.isUsingItem() && player.getUseItem().getItem() instanceof BowItem) {
            return ProjectileType.ARROW;
        }
        // Метательные: наводим, когда включён тумблер V и в руке метательный предмет.
        if (throwAimEnabled
                && (isThrowable(player.getMainHandItem()) || isThrowable(player.getOffhandItem()))) {
            return ProjectileType.THROWN;
        }
        return null;
    }

    private static boolean isThrowable(ItemStack stack) {
        return stack.is(Items.SNOWBALL) || stack.is(Items.EGG) || stack.is(Items.ENDER_PEARL);
    }

    /** Если цель уже захвачена и всё ещё валидна — держим её (никаких перескоков). */
    private static Entity keepLockedTarget(Minecraft mc, LocalPlayer player, Vec3 eye, double range) {
        if (lockedTargetId < 0) return null;
        Entity e = mc.level.getEntity(lockedTargetId);
        if (e == null || !(e instanceof LivingEntity living) || !living.isAlive()) { lockedTargetId = -1; return null; }
        if (!isValidCandidate(mc, player, eye, e, range, KEEP_FOV_DEG)) { lockedTargetId = -1; return null; }
        return e;
    }

    /** Ищет живую цель, ближайшую к перекрестью, строго в конусе взгляда. */
    private static Entity findTarget(Minecraft mc, LocalPlayer player, Vec3 eye, double range) {
        Vec3 look = player.getViewVector(1.0f);
        Entity best = null;
        double bestAngle = Double.MAX_VALUE;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player || !(e instanceof LivingEntity living) || !living.isAlive()) continue;
            if (!isValidCandidate(mc, player, eye, e, range, LOCK_FOV_DEG)) continue;

            Vec3 to = e.position().add(0, e.getBbHeight() * 0.65, 0).subtract(eye);
            double angle = Math.toDegrees(angleBetween(look, to));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = e;
            }
        }
        return best;
    }

    /** Общая проверка кандидата: тип, дальность, конус, видимость хотя бы части хитбокса. */
    private static boolean isValidCandidate(Minecraft mc, LocalPlayer player, Vec3 eye,
                                            Entity e, double range, double fovDeg) {
        boolean isPlayer = e instanceof Player;
        if (isPlayer && !PvpConfig.AIM_TARGET_PLAYERS.get()) return false;
        if (!isPlayer && !PvpConfig.AIM_TARGET_MOBS.get()) return false;

        Vec3 point = e.position().add(0, e.getBbHeight() * 0.65, 0);
        Vec3 to = point.subtract(eye);
        double dist = to.length();
        if (dist > range || dist < 1.0e-3) return false;

        // Только то, что в конусе взгляда: корова за спиной не подойдёт.
        double angle = Math.toDegrees(angleBetween(player.getViewVector(1.0f), to));
        if (angle > fovDeg) return false;

        // Достаточно, чтобы была видна ХОТЬ КАКАЯ-ТО часть хитбокса (голова из-за укрытия).
        return Los.visibleAimPoint(mc.level, player, eye, e) != null;
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        double denom = a.length() * b.length();
        if (denom < 1.0e-9) return Math.PI;
        return Math.acos(Mth.clamp(a.dot(b) / denom, -1.0, 1.0));
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }
}
