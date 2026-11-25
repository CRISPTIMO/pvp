package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import com.pvphelper.util.Sfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * ТНТ-комбо по бинду (H): смотришь на блок-опору в 3–5.5 блоках, жмёшь бинд —
 * мод сам: ставит рельсу → ставит вагонетку с ТНТ → ставит блок-стенку между
 * тобой и вагонеткой → натягивает лук до полного → стреляет в вагонетку.
 *
 * Устойчиво к движению игрока: каждый шаг ПРОВЕРЯЕТ результат и повторяет
 * попытку (до 3 раз), стенка считается от текущей позиции в сторону вагонетки,
 * в прыжке ждём приземления, а если игрок стоит в месте стенки — просим отойти.
 *
 * ВАЖНО: от ГОРЯЩЕЙ стрелы вагонетка с ТНТ взрывается МГНОВЕННО (без фьюза),
 * поэтому стенка ставится ДО выстрела, а лук обязан иметь «Воспламенение».
 */
public final class CartBomb {

    private enum Stage { IDLE, PLACE_RAIL, PLACE_CART, PLACE_WALL, DRAW, RESTORE }

    private static Stage stage = Stage.IDLE;
    private static int delay = 0;          // пауза между действиями (тики)
    private static int watchdog = 0;       // общий предохранитель по времени
    private static int retries = 0;        // попытки текущего шага
    private static boolean askedToMove = false; // чтобы не спамить «отойди»
    private static BlockHitResult railClick; // куда кликаем рельсой
    private static BlockPos railPos;       // где должна оказаться рельса
    private static BlockPos wallPos;       // место стенки: фиксируется ОДИН раз
    private static int savedSlot = -1;

    private static final int RETRY_DELAY = 2;
    private static final int MAX_RETRIES = 3;
    // 14 тиков натяга: сила ~0.8, скорость ~2.4 бл/т — на 5.5 блоках дроп < 0.15,
    // поджиг вагонетки от силы не зависит. Экономит ~0.4 c против полного натяга.
    private static final int FULL_DRAW_TICKS = 14;
    private static final int WATCHDOG_TICKS = 160;
    // Целимся в ВЕРХ вагонетки — так стрела проходит над стенкой даже в упор.
    private static final double MIN_DIST = 1.5;
    private static final double MAX_DIST = 5.5;

    private CartBomb() {}

    public static boolean isRunning() {
        return stage != Stage.IDLE;
    }

    /** Нажатие бинда: валидация ресурсов и цели, запуск последовательности. */
    public static void trigger(Minecraft mc) {
        if (!PvpConfig.CART_BOMB_ENABLED.get()) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null || mc.screen != null) return;
        if (isRunning()) { msg(mc, "§cкомбо уже выполняется"); return; }

        // Цель: блок под перекрестьем на нужной дистанции.
        if (!(mc.hitResult instanceof BlockHitResult bhr)
                || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            fail(mc, "смотри на блок-опору (3-5 блоков)");
            return;
        }
        double dist = Math.sqrt(
            player.getEyePosition(1.0f).distanceToSqr(Vec3.atCenterOf(bhr.getBlockPos())));
        if (dist < MIN_DIST) { fail(mc, "слишком близко — отойди"); return; }
        if (dist > MAX_DIST) { fail(mc, "слишком далеко"); return; }

        // Ресурсы: рельса, вагонетка с ТНТ, блок для стенки, лук с Воспламенением, стрелы.
        if (findSlot(player, CartBomb::isRail) < 0)        { fail(mc, "нет рельс"); return; }
        if (findSlot(player, s -> s.is(Items.TNT_MINECART)) < 0) { fail(mc, "нет вагонетки с ТНТ"); return; }
        if (findSlot(player, CartBomb::isWallBlock) < 0)   { fail(mc, "нет блока для стенки"); return; }
        int bowSlot = findSlot(player, CartBomb::isFlameBow);
        if (bowSlot < 0) {
            fail(mc, findSlot(player, s -> s.getItem() instanceof BowItem) >= 0
                ? "нужен лук с Воспламенением (иначе не взорвётся)"
                : "нет лука");
            return;
        }
        if (!player.getAbilities().instabuild
                && findSlot(player, s -> s.getItem() instanceof ArrowItem) < 0) {
            fail(mc, "нет стрел");
            return;
        }

        railClick = bhr;
        railPos = bhr.getBlockPos().relative(bhr.getDirection());
        wallPos = null;
        savedSlot = player.getInventory().getSelectedSlot();
        stage = Stage.PLACE_RAIL;
        delay = 0;
        watchdog = 0;
        retries = 0;
        askedToMove = false;
        msg(mc, "§eкомбо пошло — постой пару секунд на месте");
    }

    public static void tick(Minecraft mc) {
        if (stage == Stage.IDLE) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.level == null) { abort(mc, null); return; }

        if (++watchdog > WATCHDOG_TICKS) { abort(mc, "не уложились по времени"); return; }
        if (delay > 0) { delay--; return; }

        switch (stage) {
            // Шаблон каждого шага: сначала проверяем результат, потом (пере)пытаемся.
            case PLACE_RAIL -> {
                if (mc.level.getBlockState(railPos).getBlock() instanceof BaseRailBlock) {
                    next(Stage.PLACE_CART);
                    return;
                }
                if (retries >= MAX_RETRIES) { abort(mc, "рельса не встала (далеко/нет места?)"); return; }
                if (!equip(mc, player, CartBomb::isRail)) { abort(mc, "рельсы пропали"); return; }
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, railClick);
                player.swing(InteractionHand.MAIN_HAND);
                // Клиентское предсказание ставит блок мгновенно — первую проверку
                // делаем уже на следующем тике, ретраи чуть реже.
                delay = (retries == 0) ? 1 : RETRY_DELAY;
                retries++;
            }
            case PLACE_CART -> {
                if (findCart(mc) != null) {
                    next(Stage.PLACE_WALL);
                    return;
                }
                if (retries >= MAX_RETRIES) { abort(mc, "вагонетка не встала"); return; }
                if (!equip(mc, player, s -> s.is(Items.TNT_MINECART))) { abort(mc, "вагонетка пропала"); return; }
                BlockHitResult cartClick = new BlockHitResult(
                    Vec3.atCenterOf(railPos), Direction.UP, railPos, false);
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, cartClick);
                player.swing(InteractionHand.MAIN_HAND);
                delay = (retries == 0) ? 1 : RETRY_DELAY;
                retries++;
            }
            case PLACE_WALL -> {
                // Место стенки фиксируем ОДИН раз (между игроком и вагонеткой):
                // иначе при отходе назад каждая попытка лепит новый блок,
                // и эта куча перекрывает выстрел.
                if (wallPos == null) {
                    double dx = railPos.getX() + 0.5 - player.getX();
                    double dz = railPos.getZ() + 0.5 - player.getZ();
                    Direction toCart = Direction.fromYRot(Math.toDegrees(Math.atan2(-dx, dz)));
                    wallPos = player.blockPosition().relative(toCart);
                }

                if (!mc.level.getBlockState(wallPos).canBeReplaced()) {
                    next(Stage.DRAW); // стенка уже есть (наша или чужая)
                    return;
                }
                // Игрок стоит в месте стенки — блок не поставится, просим сдвинуться.
                if (player.getBoundingBox().intersects(new AABB(wallPos))) {
                    if (!askedToMove) { msg(mc, "§eшагни на полблока назад"); askedToMove = true; }
                    delay = 2;
                    return;
                }
                BlockPos support = wallPos.below();
                if (mc.level.getBlockState(support).isAir()) {
                    if (!player.onGround()) { delay = 2; return; } // в прыжке — ждём приземления
                    abort(mc, "стенку некуда ставить (под ней воздух)");
                    return;
                }
                if (retries >= MAX_RETRIES + 2) { abort(mc, "стенка не встала"); return; }
                if (!equip(mc, player, CartBomb::isWallBlock)) { abort(mc, "блок для стенки пропал"); return; }
                BlockHitResult wallClick = new BlockHitResult(
                    Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, wallClick);
                player.swing(InteractionHand.MAIN_HAND);
                delay = (retries == 0) ? 1 : RETRY_DELAY;
                retries++;
            }
            case DRAW -> {
                MinecartTNT cart = findCart(mc);
                if (cart == null) { abort(mc, "вагонетку сбили"); return; }

                // Целимся в ВЕРХ вагонетки каждый тик натяга (клиренс над стенкой).
                aimAt(player, cart.position().add(0, 0.55, 0));

                if (!player.isUsingItem()) {
                    if (!equip(mc, player, CartBomb::isFlameBow)) { abort(mc, "лук пропал"); return; }
                    // Держим ПКМ программно: иначе ваниль каждый тик сама
                    // отпускает использование (handleKeybinds) и натяг сбрасывается.
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                } else if (player.getTicksUsingItem() >= FULL_DRAW_TICKS) {
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(player); // выстрел
                    next(Stage.RESTORE);
                }
            }
            case RESTORE -> {
                selectSlot(mc, savedSlot);
                stage = Stage.IDLE;
                Sfx.toggleOn();
                msg(mc, "§aвыстрел! сиди за стенкой");
            }
            case IDLE -> {}
        }
    }

    private static void next(Stage s) {
        stage = s;
        retries = 0;
        delay = 1;
        askedToMove = false;
    }

    // ---------- предикаты предметов ----------

    private static boolean isRail(ItemStack s) {
        return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL)
            || s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL);
    }

    /** Обычный полный блок, которым можно закрыться. */
    private static boolean isWallBlock(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        if (isRail(s) || s.is(Items.TNT)) return false;
        var state = bi.getBlock().defaultBlockState();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Лук с «Воспламенением» — только горящая стрела взрывает вагонетку. */
    private static boolean isFlameBow(ItemStack s) {
        if (!(s.getItem() instanceof BowItem)) return false;
        ItemEnchantments ench = s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var holder : ench.keySet()) {
            if (holder.is(Enchantments.FLAME)) return true;
        }
        return false;
    }

    // ---------- вспомогательные ----------

    private static MinecartTNT findCart(Minecraft mc) {
        List<MinecartTNT> carts = mc.level.getEntitiesOfClass(
            MinecartTNT.class, new AABB(railPos).inflate(1.0));
        return carts.isEmpty() ? null : carts.get(0);
    }

    /** Мгновенная наводка (скриптовое действие — плавность тут не нужна). */
    private static void aimAt(LocalPlayer player, Vec3 point) {
        Vec3 eye = player.getEyePosition(1.0f);
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setXRot((float) Math.max(-90.0, Math.min(90.0,
            -Math.toDegrees(Math.atan2(dy, horiz)))));
    }

    /** Ищет предмет по всему инвентарю (0-35), -1 если нет. */
    private static int findSlot(LocalPlayer player, Predicate<ItemStack> what) {
        for (int i = 0; i < 36; i++) {
            if (what.test(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    /** Берёт предмет в главную руку: хотбар — выбором слота, инвентарь — свапом. */
    private static boolean equip(Minecraft mc, LocalPlayer player, Predicate<ItemStack> what) {
        if (what.test(player.getMainHandItem())) return true;
        int slot = findSlot(player, what);
        if (slot < 0) return false;
        if (slot < 9) {
            selectSlot(mc, slot);
        } else {
            int cur = player.getInventory().getSelectedSlot();
            mc.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId, slot, cur, ClickType.SWAP, player);
        }
        return what.test(player.getMainHandItem());
    }

    private static void selectSlot(Minecraft mc, int slot) {
        LocalPlayer player = mc.player;
        if (player == null || slot < 0 || player.getInventory().getSelectedSlot() == slot) return;
        player.getInventory().setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private static void abort(Minecraft mc, String reason) {
        stage = Stage.IDLE;
        wallPos = null;
        if (mc != null) {
            mc.options.keyUse.setDown(false); // отпустить программный ПКМ, если натягивали
            if (mc.player != null) {
                selectSlot(mc, savedSlot);
                if (reason != null) fail(mc, reason);
            }
        }
        savedSlot = -1;
    }

    private static void fail(Minecraft mc, String reason) {
        Sfx.deny();
        msg(mc, "§c" + reason);
    }

    private static void msg(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§6[ТНТ] §f" + text), true);
        }
    }
}
