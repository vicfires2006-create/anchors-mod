package com.anchors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Ставит рядом с игроком сундук, в котором лежит дневник. */
public class DiaryChest {

    /** Куда пробуем поставить сундук (смещения от игрока по x и z), по порядку. */
    private static final int[][] OFFSETS = {
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {3, 2}, {-3, 2}, {3, -2}, {-3, -2},
            {4, 0}, {-4, 0}, {0, 4}, {0, -4}
    };

    public static void spawnNear(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        BlockPos target = null;

        for (int[] offset : OFFSETS) {
            int x = base.getX() + offset[0];
            int z = base.getZ() + offset[1];
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos below = pos.below();

            boolean closeEnough = Math.abs(y - base.getY()) <= 3;
            boolean freeSpot = level.getBlockState(pos).isAir();
            boolean solidGround = level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);

            if (closeEnough && freeSpot && solidGround) {
                target = pos;
                break;
            }
        }
        if (target == null) {
            target = base.east(); // запасной вариант, если вокруг всё неудобное
        }

        // Сундук открывается "лицом" к игроку
        Direction facing = Direction.getNearest(
                player.getX() - (target.getX() + 0.5), 0.0, player.getZ() - (target.getZ() + 0.5));
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
        level.setBlock(target, chest, 3);

        if (level.getBlockEntity(target) instanceof ChestBlockEntity chestEntity) {
            chestEntity.setItem(13, DiaryBook.create()); // слот 13 = центр сундука
            chestEntity.setChanged();
        }
    }
}
