package com.anchors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Заброшенные постройки с якорями. Строятся кодом рядом с игроком, когда приходит время.
 *
 * Три вида:
 *   CABIN  - заброшенный дом, внутри старый фонарь
 *   CELLAR - развалины с люком в подвал, внизу музыкальная шкатулка
 *   RUINS  - каменные руины с алтарём, на нём сундук с тряпичной куклой
 */
public class AbandonedStructures {

    public enum Kind { CABIN, CELLAR, RUINS }

    /** Как далеко от игрока строить (в блоках). */
    private static final int MIN_DISTANCE = 50;
    private static final int MAX_DISTANCE = 90;

    /** Сколько разных мест пробовать, прежде чем сдаться и повторить позже. */
    private static final int MAX_ATTEMPTS = 25;

    /** Строит постройку нужного вида рядом с игроком. Возвращает место или null, если подходящего места не нашлось. */
    public static BlockPos spawnNear(ServerPlayer player, Kind kind) {
        ServerLevel level = player.serverLevel();
        RandomSource random = level.random;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            int x = (int) Math.round(player.getX() + Math.cos(angle) * dist);
            int z = (int) Math.round(player.getZ() + Math.sin(angle) * dist);

            BlockPos origin = findSite(level, x, z);
            if (origin == null) continue;

            Builder builder = new Builder(level, origin, random);
            switch (kind) {
                case CABIN -> builder.cabin(new ItemStack(ModItems.OLD_LANTERN.get()));
                case CELLAR -> builder.cellar(new ItemStack(ModItems.MUSIC_BOX.get()));
                case RUINS -> builder.ruins(new ItemStack(ModItems.RAG_DOLL.get()));
            }
            return origin;
        }
        return null;
    }

    /** Проверяет, что место ровное, твёрдое и сухое. Возвращает точку, где будет пол (первый свободный блок над землёй). */
    private static BlockPos findSite(ServerLevel level, int cx, int cz) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                int x = cx + dx;
                int z = cz + dz;
                level.getChunk(x >> 4, z >> 4); // загружаем (или создаём) кусок мира

                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y <= level.getMinBuildHeight() + 1) return null;

                BlockPos ground = new BlockPos(x, y - 1, z);
                BlockState state = level.getBlockState(ground);
                if (!level.getFluidState(ground).isEmpty()) return null;
                if (!state.isFaceSturdy(level, ground, Direction.UP)) return null;

                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (max - min > 3) return null; // слишком неровно

        int centerY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        return new BlockPos(cx, centerY, cz);
    }

    /** Помощник: ставит блоки относительно точки origin (dy = 0 это уровень пола, dy = -1 сам пол). */
    private static final class Builder {
        private final ServerLevel level;
        private final BlockPos origin;
        private final RandomSource random;

        private static final BlockState AIR = Blocks.AIR.defaultBlockState();

        Builder(ServerLevel level, BlockPos origin, RandomSource random) {
            this.level = level;
            this.origin = origin;
            this.random = random;
        }

        void set(int dx, int dy, int dz, BlockState state) {
            level.setBlock(origin.offset(dx, dy, dz), state, 2);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                        set(x, y, z, state);
        }

        /** Ставит блок, но с шансом missingChance он "развалился" и его нет. */
        void ruined(int dx, int dy, int dz, BlockState state, float missingChance) {
            if (random.nextFloat() >= missingChance) {
                set(dx, dy, dz, state);
            }
        }

        /** Расчищает площадку от деревьев и травы и подсыпает землю под будущий пол. */
        void prepare(int half, int height) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    for (int dy = 0; dy <= height; dy++) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, AIR, 2);
                        }
                    }
                    for (int dy = -1; dy >= -8; dy--) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) {
                            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        /** Сундук с якорем в центре и немного хлама. */
        void chest(int dx, int dy, int dz, Direction facing, ItemStack anchor) {
            BlockPos pos = origin.offset(dx, dy, dz);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), 2);

            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                chest.setItem(13, anchor);

                Item[] junk = {Items.ROTTEN_FLESH, Items.BONE, Items.STICK, Items.PAPER};
                for (int i = 0; i < 3; i++) {
                    int slot = random.nextInt(27);
                    if (slot == 13) continue;
                    chest.setItem(slot, new ItemStack(junk[random.nextInt(junk.length)], 1 + random.nextInt(3)));
                }
                chest.setChanged();
            }
        }

        BlockState pickStone() {
            float r = random.nextFloat();
            if (r < 0.35F) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            if (r < 0.60F) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
            if (r < 0.70F) return Blocks.COBBLESTONE.defaultBlockState();
            return Blocks.STONE_BRICKS.defaultBlockState();
        }

        // ---------- ЗАБРОШЕННЫЙ ДОМ ----------
        void cabin(ItemStack anchor) {
            prepare(3, 7);

            BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
            BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
            BlockState mossy = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState();
            BlockState web = Blocks.COBWEB.defaultBlockState();

            // пол
            for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++)
                    set(x, -1, z, random.nextFloat() < 0.25F ? mossy : planks);

            // стены (местами развалились)
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
                    boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
                    for (int dy = 0; dy <= 3; dy++) {
                        if (corner) ruined(x, dy, z, log, dy == 3 ? 0.30F : 0.05F);
                        else ruined(x, dy, z, planks, dy == 3 ? 0.40F : 0.12F);
                    }
                }
            }

            // окна (пустые или затянутые паутиной) и дверной проём
            int[][] windows = {{3, 0}, {-3, 0}, {0, -3}};
            for (int[] w : windows) {
                set(w[0], 1, w[1], random.nextFloat() < 0.6F ? web : AIR);
                set(w[0], 2, w[1], AIR);
            }
            set(0, 0, 3, AIR);
            set(0, 1, 3, AIR);

            // дырявая крыша
            for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++)
                    ruined(x, 4, z, planks, 0.30F);
            for (int x = -1; x <= 1; x++)
                for (int z = -3; z <= 3; z++)
                    ruined(x, 5, z, slab, 0.40F);

            // внутри: потухший костёр, столик, паутина, сундук
            set(0, 0, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            set(2, 0, 1, Blocks.OAK_FENCE.defaultBlockState());
            set(2, 1, 1, Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
            int[][] corners = {{-2, -2}, {2, -2}, {2, 2}, {-2, 2}};
            for (int[] c : corners) {
                if (random.nextFloat() < 0.7F) set(c[0], 3, c[1], web);
            }
            chest(-2, 0, -2, Direction.SOUTH, anchor);
        }

        // ---------- РАЗВАЛИНЫ С ПОДВАЛОМ ----------
        void cellar(ItemStack anchor) {
            prepare(4, 5);

            BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
            BlockState web = Blocks.COBWEB.defaultBlockState();

            // каменная коробка 7x7 под землёй (dy от -5 до -1)
            for (int x = -3; x <= 3; x++)
                for (int z = -3; z <= 3; z++)
                    for (int dy = -5; dy <= -1; dy++)
                        set(x, dy, z, pickStone());

            // внутри вырезаем комнату 5x5 высотой 3
            fill(-2, -4, -2, 2, -2, 2, AIR);

            // потолок = деревянный пол развалин (местами прогнил)
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    boolean overRoom = Math.abs(x) <= 2 && Math.abs(z) <= 2;
                    if (overRoom && random.nextFloat() < 0.10F) set(x, -1, z, AIR);
                    else set(x, -1, z, planks);
                }
            }

            // люк с лестницей: стоит у северной стены комнаты и ведёт на поверхность
            set(0, -1, -2, AIR);
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
            for (int dy = -4; dy <= -1; dy++) {
                set(0, dy, -2, ladder);
            }

            // низкая разрушенная ограда по краю площадки
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                    ruined(x, 0, z, pickStone(), 0.25F);
                    ruined(x, 1, z, pickStone(), 0.60F);
                }
            }

            // комната: сундук, свеча, столик, паутина
            chest(0, -4, 2, Direction.NORTH, anchor);
            set(2, -4, -1, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
            set(-2, -4, 0, Blocks.OAK_FENCE.defaultBlockState());
            set(-2, -3, 0, Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
            int[][] corners = {{-2, -2}, {2, -2}, {2, 2}, {-2, 2}};
            for (int[] c : corners) {
                set(c[0], -2, c[1], web);
            }
        }

        // ---------- КАМЕННЫЕ РУИНЫ ----------
        void ruins(ItemStack anchor) {
            prepare(4, 6);

            BlockState web = Blocks.COBWEB.defaultBlockState();

            // пол: каменные плиты вперемешку с землёй
            for (int x = -4; x <= 4; x++)
                for (int z = -4; z <= 4; z++)
                    if (random.nextFloat() > 0.15F) set(x, -1, z, pickStone());

            // обломки стен по краю
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                    int height = random.nextInt(4); // 0..3
                    for (int dy = 0; dy < height; dy++) set(x, dy, z, pickStone());
                }
            }

            // колонны
            int[][] pillars = {{-4, -4}, {4, -4}, {-4, 4}, {4, 4}, {0, -4}, {-4, 0}, {4, 0}};
            for (int[] p : pillars) {
                int height = 3 + random.nextInt(3); // 3..5
                for (int dy = 0; dy < height; dy++) set(p[0], dy, p[1], Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
                if (random.nextFloat() < 0.6F) set(p[0] + (p[0] == 0 ? 0 : (p[0] > 0 ? -1 : 1)), height - 1, p[1] + (p[1] == 0 ? 0 : (p[1] > 0 ? -1 : 1)), web);
            }

            // алтарь в центре, на нём сундук
            set(0, 0, 0, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            chest(0, 1, 0, Direction.SOUTH, anchor);

            // два синих огня по бокам алтаря
            set(-2, 0, 0, Blocks.SOUL_LANTERN.defaultBlockState());
            set(2, 0, 0, Blocks.SOUL_LANTERN.defaultBlockState());
        }
    }
}
