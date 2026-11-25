package com.pvphelper.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Численный решатель баллистики. Симулирует полёт снаряда потик-в-тик
 * с гравитацией и сопротивлением воздуха (как это делает сам Minecraft),
 * и подбирает угол наклона (pitch), чтобы попасть в цель, а также угол
 * поворота (yaw) с упреждением на скорость движущейся цели.
 */
public final class BallisticSolver {

    /** Параметры снаряда: как Minecraft разгоняет и тормозит разные projectile. */
    public enum ProjectileType {
        // Полностью заряженный лук: скорость 3.0 бл/тик, гравитация 0.05, дрэг 0.99.
        ARROW(3.0, 0.05, 0.99),
        // Снежок / яйцо / жемчуг Края: скорость 1.5, гравитация 0.03, дрэг 0.99.
        THROWN(1.5, 0.03, 0.99);

        public final double speed;
        public final double gravity;
        public final double drag;

        ProjectileType(double speed, double gravity, double drag) {
            this.speed = speed;
            this.gravity = gravity;
            this.drag = drag;
        }
    }

    public static final class Solution {
        public final float yaw;
        public final float pitch;
        public final int flightTicks;

        Solution(float yaw, float pitch, int flightTicks) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.flightTicks = flightTicks;
        }
    }

    private BallisticSolver() {}

    /** Как solve(..., speed), но со стандартной скоростью снаряда (полный натяг лука). */
    public static Solution solve(Vec3 eye, Entity target, ProjectileType type) {
        return solve(eye, target, type, type.speed);
    }

    /** Как solve(..., aimHeight), но с прицелом в центр хитбокса. */
    public static Solution solve(Vec3 eye, Entity target, ProjectileType type, double speed) {
        return solve(eye, target, type, speed, target.getBbHeight() * 0.5);
    }

    /**
     * Находит угол выстрела из позиции глаз стрелка в движущуюся цель.
     * speed — начальная скорость снаряда; aimHeight — высота точки прицеливания
     * над позицией цели (позволяет целиться в видимую часть частично укрытой цели).
     * Возвращает null, если цель недостижима с данным снарядом.
     */
    public static Solution solve(Vec3 eye, Entity target, ProjectileType type, double speed, double aimHeight) {
        Vec3 targetVel = new Vec3(
            target.getX() - target.xOld,
            target.getY() - target.yOld,
            target.getZ() - target.zOld);
        return solve(eye, target, type, speed, aimHeight, targetVel);
    }

    /**
     * Как выше, но со ЯВНОЙ скоростью цели (бл/тик). Позволяет передать
     * сглаженную по нескольким тикам скорость — против дёргающихся целей это
     * стабильнее, чем разница за один тик.
     */
    public static Solution solve(Vec3 eye, Entity target, ProjectileType type,
                                 double speed, double aimHeight, Vec3 targetVel) {
        Solution best = null;
        // Итеративное упреждение: 4 прохода обычно сходятся.
        int leadTicks = 0;
        for (int iter = 0; iter < 5; iter++) {
            Vec3 predicted = new Vec3(
                target.getX() + targetVel.x * leadTicks,
                target.getY() + aimHeight + targetVel.y * leadTicks,
                target.getZ() + targetVel.z * leadTicks);

            double dx = predicted.x - eye.x;
            double dy = predicted.y - eye.y;
            double dz = predicted.z - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);

            float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

            int[] flight = new int[1];
            Float pitch = solvePitch(horiz, dy, type, speed, flight);
            if (pitch == null) {
                return best; // недостижимо на этой итерации — отдаём что было
            }

            best = new Solution(yaw, pitch, flight[0]);
            if (flight[0] == leadTicks) {
                break; // упреждение стабилизировалось
            }
            leadTicks = flight[0];
        }
        return best;
    }

    /**
     * Подбирает pitch (в конвенции Minecraft: -90 = вверх, +90 = вниз),
     * при котором снаряд на горизонтальной дистанции horiz окажется на высоте dy.
     * Ищет прямую (настильную) траекторию — наименьший угол, который достаёт до цели.
     * Пишет число тиков полёта в flightOut[0].
     */
    private static Float solvePitch(double horiz, double dy, ProjectileType type, double speed, int[] flightOut) {
        double prevY = Double.NEGATIVE_INFINITY;
        double prevPitch = 0;
        boolean havePrev = false;
        // Сканируем от почти отвесного вниз (89°, для целей прямо под нами) к навесному
        // вверх. Слишком крутой угол может не докрыть горизонталь (NaN) — такие
        // пропускаем, а не сдаёмся. Для достижимых углов y монотонно растёт.
        for (double pitchDeg = 89.0; pitchDeg >= -89.0; pitchDeg -= 0.25) {
            int[] ticks = new int[1];
            double y = simulate(pitchDeg, horiz, type, speed, ticks);
            if (Double.isNaN(y)) {
                continue; // с этого (слишком крутого) угла снаряд не долетает по горизонтали
            }
            if (y >= dy) {
                // Пересекли высоту цели: линейно уточняем между двумя углами.
                double resultPitch = pitchDeg;
                if (havePrev && y != prevY) {
                    double frac = (dy - prevY) / (y - prevY);
                    resultPitch = prevPitch + (pitchDeg - prevPitch) * frac;
                }
                flightOut[0] = ticks[0];
                return (float) resultPitch;
            }
            prevY = y;
            prevPitch = pitchDeg;
            havePrev = true;
        }
        return null;
    }

    /**
     * Симулирует полёт снаряда под углом pitchDeg и возвращает высоту (относительно
     * точки выпуска) в момент прохождения горизонтальной дистанции horiz.
     * NaN — если снаряд не долетает по горизонтали за отведённое время.
     */
    private static double simulate(double pitchDeg, double horiz, ProjectileType type, double speed, int[] ticksOut) {
        double pitch = Math.toRadians(pitchDeg);
        double vh = Math.cos(pitch) * speed;      // горизонтальная скорость
        double vy = -Math.sin(pitch) * speed;     // вертикальная (вверх +)

        double x = 0, y = 0;
        for (int t = 0; t < 400; t++) {
            double prevX = x, prevY = y;
            x += vh;
            y += vy;
            vh *= type.drag;
            vy *= type.drag;
            vy -= type.gravity;

            if (x >= horiz) {
                double frac = (x - prevX) == 0 ? 0 : (horiz - prevX) / (x - prevX);
                ticksOut[0] = t + 1;
                return prevY + (y - prevY) * frac;
            }
        }
        return Double.NaN;
    }
}
