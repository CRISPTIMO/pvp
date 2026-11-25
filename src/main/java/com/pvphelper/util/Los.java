package com.pvphelper.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Проверка прямой видимости (line of sight): нет ли блока между двумя точками. */
public final class Los {

    private Los() {}

    /** true, если между from и to нет твёрдого блока (жидкости игнорируем). */
    public static boolean clear(Level level, Entity viewer, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(
            from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer);
        return level.clip(ctx).getType() == HitResult.Type.MISS;
    }

    // Проверяемые высоты цели (доли от роста): грудь, голова, ноги.
    // Если из-за укрытия торчит только голова — целимся в голову.
    private static final double[] AIM_FRACTIONS = {0.65, 0.9, 0.2};

    /**
     * Ищет ВИДИМУЮ точку прицеливания на цели: проверяет несколько высот
     * (грудь → голова → ноги) и возвращает первую, до которой нет блока.
     * null — цель полностью за укрытием.
     */
    public static Vec3 visibleAimPoint(Level level, Entity viewer, Vec3 from, Entity target) {
        double h = target.getBbHeight();
        for (double frac : AIM_FRACTIONS) {
            Vec3 point = target.position().add(0, h * frac, 0);
            if (clear(level, viewer, from, point)) {
                return point;
            }
        }
        return null;
    }
}
