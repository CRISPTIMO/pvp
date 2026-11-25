package com.pvphelper.feature;

import com.pvphelper.PvpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Авто-ферма: когда игрок ломает созревший урожай, мод после разрушения
 * пересаживает нужное семя из хотбара обратно на грядку и возвращает
 * ранее выбранный слот. Работает через симуляцию клика семенем по грядке.
 */
public final class AutoFarm {

    public static boolean enabled = true;

    // Блок урожая -> предмет-семя для пересадки.
    private static final Map<Block, Item> SEEDS = new HashMap<>();
    static {
        SEEDS.put(Blocks.WHEAT, Items.WHEAT_SEEDS);
        SEEDS.put(Blocks.CARROTS, Items.CARROT);
        SEEDS.put(Blocks.POTATOES, Items.POTATO);
        SEEDS.put(Blocks.BEETROOTS, Items.BEETROOT_SEEDS);
        SEEDS.put(Blocks.NETHER_WART, Items.NETHER_WART);
    }

    /** Отложенные пересадки: где был урожай -> какое семя посадить (+ таймаут). */
    private static final Map<BlockPos, Pending> pending = new HashMap<>();

    /** Состояние одной пересадки: ждём разрушения -> сажаем с ретраями. */
    private static final class Pending {
        final Item seed;
        final int expireTick;
        boolean broken = false;   // блок уже исчез на клиенте
        int attempts = 0;         // сколько раз кликнули семенем
        int nextActionTick = 0;   // раньше этого тика ничего не делаем

        Pending(Item seed, int expireTick) {
            this.seed = seed;
            this.expireTick = expireTick;
        }
    }

    // Задержка после исчезновения блока: даём СЕРВЕРУ дожевать ломание,
    // иначе посадка приходит в ещё занятую клетку и молча отклоняется.
    private static final int PLANT_DELAY = 2;
    private static final int RETRY_DELAY = 4;   // пауза между повторными посадками
    private static final int MAX_ATTEMPTS = 3;

    // Возврат слота делаем на СЛЕДУЮЩИЙ тик после клика (не в том же тике).
    private static int restoreSlot = -1;
    private static int plantedFromSlot = -1;

    private AutoFarm() {}

    /** Вызывается при начале ломания блока: если это зрелый урожай — ставим в очередь. */
    public static void onLeftClickBlock(Minecraft mc, BlockPos pos, BlockState state) {
        if (!ready(mc)) return;
        Item seed = seedFor(state);
        if (seed == null) return;
        int now = mc.player.tickCount;
        pending.put(pos.immutable(), new Pending(seed, now + 60)); // ~3 сек на разрушение
    }

    /** Каждый тик: очередь пересадок. Сажаем с задержкой после разрушения и с ретраями. */
    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            pending.clear();
            restoreSlot = -1;
            plantedFromSlot = -1;
            return;
        }
        if (mc.screen != null) return; // экран открыт — просто ждём, очередь не теряем

        // Возврат слота, отложенный с прошлого тика (если игрок сам не переключился).
        if (restoreSlot >= 0) {
            if (player.getInventory().getSelectedSlot() == plantedFromSlot) {
                selectSlot(mc, restoreSlot);
            }
            restoreSlot = -1;
            plantedFromSlot = -1;
        }

        if (pending.isEmpty()) return;
        int now = player.tickCount;

        Iterator<Map.Entry<BlockPos, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BlockPos pos = e.getKey();
            Pending p = e.getValue();

            if (now > p.expireTick) { it.remove(); continue; }

            BlockState cur = level.getBlockState(pos);

            // Фаза 1: ждём, пока блок исчезнет; потом пауза PLANT_DELAY,
            // чтобы сервер точно успел обработать ломание.
            if (!p.broken) {
                if (cur.isAir()) {
                    p.broken = true;
                    p.nextActionTick = now + PLANT_DELAY;
                }
                continue;
            }

            if (now < p.nextActionTick) continue;

            // Фаза 2: не воздух после нашей посадки = растение встало, успех.
            if (!cur.isAir()) { it.remove(); continue; }

            // Грядка снизу должна быть подходящей (farmland / soul sand для варта).
            BlockState below = level.getBlockState(pos.below());
            boolean farmlandOk = below.is(Blocks.FARMLAND) || below.is(Blocks.SOUL_SAND);
            if (!farmlandOk) { it.remove(); continue; }

            if (p.attempts >= MAX_ATTEMPTS || !replant(mc, pos, p.seed)) {
                it.remove(); // исчерпали попытки или семян нет
                continue;
            }
            p.attempts++;
            p.nextActionTick = now + RETRY_DELAY; // проверим результат и, если надо, повторим
        }
    }

    private static boolean replant(Minecraft mc, BlockPos cropPos, Item seed) {
        LocalPlayer player = mc.player;
        int seedSlot = findInHotbar(player, seed);
        if (seedSlot < 0) return false;

        int previous = player.getInventory().getSelectedSlot();
        selectSlot(mc, seedSlot);

        // Клик семенем по верхней грани грядки (farmland под позицией урожая).
        BlockPos farmland = cropPos.below();
        Vec3 hit = new Vec3(cropPos.getX() + 0.5, cropPos.getY(), cropPos.getZ() + 0.5);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, farmland, false);

        if (mc.gameMode != null) {
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, result);
        }

        // Слот вернём на следующем тике — три пакета в один тик серверу не нравятся.
        if (previous != seedSlot && restoreSlot < 0) {
            restoreSlot = previous;
            plantedFromSlot = seedSlot;
        }
        return true;
    }

    private static Item seedFor(BlockState state) {
        Block block = state.getBlock();
        Item seed = SEEDS.get(block);
        if (seed == null) return null;
        // Сажаем только со зрелого урожая.
        if (block instanceof CropBlock crop && !crop.isMaxAge(state)) return null;
        if (block instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) < 3) return null;
        return seed;
    }

    private static int findInHotbar(LocalPlayer player, Item seed) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(seed)) return i;
        }
        return -1;
    }

    private static void selectSlot(Minecraft mc, int slot) {
        LocalPlayer player = mc.player;
        if (player.getInventory().getSelectedSlot() == slot) return;
        player.getInventory().setSelectedSlot(slot);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private static boolean ready(Minecraft mc) {
        return enabled && PvpConfig.AUTO_FARM_ENABLED.get()
            && mc.player != null && mc.screen == null;
    }
}
