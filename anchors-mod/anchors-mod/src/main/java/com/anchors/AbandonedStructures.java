package com.anchors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Заброшенные постройки с якорями. Строятся кодом рядом с игроком, когда приходит время.
 * После ритуала те же постройки "чинятся" (restore): стены целые, окна со стёклами, паутины и лиан нет.
 *
 * Три вида:
 *   CABIN  - каменный дом с крыльцом, внутри старый фонарь и запись 1
 *   CELLAR - развалины с люком в большой подвал, внизу музыкальная шкатулка и запись 2
 *   RUINS  - каменные руины с алтарём, на нём сундук с тряпичной куклой и записью 3
 */
public class AbandonedStructures {

    /** Метка на раме над алтарём: в неё нужно вставить перевёрнутый крест. */
    public static final String TAG_ALTAR_FRAME = "anchors_altar_frame";

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

            Builder builder = new Builder(level, origin, random, false);
            switch (kind) {
                case CABIN -> builder.cabin(new ItemStack(ModItems.OLD_LANTERN.get()), DiaryBook.entry(1));
                case CELLAR -> builder.cellar(new ItemStack(ModItems.MUSIC_BOX.get()), DiaryBook.entry(2));
                case RUINS -> builder.ruins(new ItemStack(ModItems.RAG_DOLL.get()), DiaryBook.entry(3));
            }

            // Запоминаем постройку (чтобы потом починить) и алтарь (для ритуала)
            FadingData data = FadingData.get(level.getServer());
            data.addStructure(kind.ordinal(), origin);
            if (kind == Kind.RUINS) {
                data.setAltarPos(origin);
            }
            return origin;
        }
        return null;
    }

    /** Чинит постройку: после ритуала мир "вспоминает" себя. Сундуки и их содержимое не трогает. */
    public static void restore(ServerLevel level, BlockPos origin, Kind kind) {
        // убеждаемся, что кусок мира загружен
        for (int dx = -5; dx <= 5; dx += 5)
            for (int dz = -5; dz <= 5; dz += 5)
                level.getChunk((origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4);

        Builder builder = new Builder(level, origin, level.random, true);
        switch (kind) {
            case CABIN -> builder.cabin(ItemStack.EMPTY, ItemStack.EMPTY);
            case CELLAR -> builder.cellar(ItemStack.EMPTY, ItemStack.EMPTY);
            case RUINS -> builder.ruins(ItemStack.EMPTY, ItemStack.EMPTY);
        }
    }

    /** Проверяет, что место ровное, твёрдое и сухое. Возвращает точку, где будет пол (первый свободный блок над землёй). */
    private static BlockPos findSite(ServerLevel level, int cx, int cz) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int dx = -5; dx <= 5; dx += 5) {
            for (int dz = -5; dz <= 5; dz += 5) {
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

    /**
     * Помощник: ставит блоки относительно точки origin (dy = 0 это уровень пола, dy = -1 сам пол).
     * Ось x идёт на восток, z на юг. Если restored = true, строит "целую" версию:
     * без дыр, без паутины и лиан, с чистыми камнями.
     */
    private static final class Builder {
        private static final BlockState AIR = Blocks.AIR.defaultBlockState();

        private final ServerLevel level;
        private final BlockPos origin;
        private final RandomSource random;
        private final boolean restored;

        Builder(ServerLevel level, BlockPos origin, RandomSource random, boolean restored) {
            this.level = level;
            this.origin = origin;
            this.random = random;
            this.restored = restored;
        }

        // ---------- базовые операции ----------

        void set(int dx, int dy, int dz, BlockState state) {
            level.setBlock(origin.offset(dx, dy, dz), state, 2);
        }

        void setIfAir(int dx, int dy, int dz, BlockState state) {
            BlockPos pos = origin.offset(dx, dy, dz);
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, state, 2);
            }
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                        set(x, y, z, state);
        }

        /** Ставит блок, но с шансом missingChance он "развалился" и его нет (в целой версии стоит всегда). */
        void ruined(int dx, int dy, int dz, BlockState state, float missingChance) {
            if (restored || random.nextFloat() >= missingChance) {
                set(dx, dy, dz, state);
            }
        }

        // ---------- готовые блоки ----------

        BlockState web() {
            return restored ? AIR : Blocks.COBWEB.defaultBlockState();
        }

        BlockState stair(Block block, Direction facing) {
            return block.defaultBlockState().setValue(StairBlock.FACING, facing);
        }

        BlockState slabTop(Block block) {
            return block.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        }

        BlockState log(Block block, Direction.Axis axis) {
            return block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
        }

        BlockState leaves() {
            return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        }

        BlockState barrel() {
            Direction facing = random.nextBoolean() ? Direction.UP : Direction.NORTH;
            return Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, facing);
        }

        BlockState vine(Direction attachedTo) {
            return Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(attachedTo), true);
        }

        BlockState pickStone() {
            if (restored) return Blocks.STONE_BRICKS.defaultBlockState();
            float r = random.nextFloat();
            if (r < 0.35F) return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            if (r < 0.60F) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
            if (r < 0.70F) return Blocks.COBBLESTONE.defaultBlockState();
            return Blocks.STONE_BRICKS.defaultBlockState();
        }

        /** Булыжные стены дома: с мхом и вперемешку с камнем (после починки аккуратные). */
        BlockState pickWall() {
            float r = random.nextFloat();
            if (restored) return r < 0.80F ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState();
            if (r < 0.50F) return Blocks.COBBLESTONE.defaultBlockState();
            if (r < 0.78F) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            if (r < 0.90F) return Blocks.STONE.defaultBlockState();
            return Blocks.ANDESITE.defaultBlockState();
        }

        /** Тёмный пол подвала. */
        BlockState pickCellarFloor() {
            float r = random.nextFloat();
            if (restored) return r < 0.7F ? Blocks.DEEPSLATE_BRICKS.defaultBlockState() : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            if (r < 0.45F) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            if (r < 0.75F) return Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            if (r < 0.90F) return Blocks.COBBLESTONE.defaultBlockState();
            return Blocks.GRAVEL.defaultBlockState();
        }

        // ---------- уборка и подготовка ----------

        /** Убирает паутину и лианы в области вокруг центра (при починке). */
        void cleanDecay(int halfX, int halfZ, int minDy, int maxDy) {
            for (int dx = -halfX; dx <= halfX; dx++)
                for (int dy = minDy; dy <= maxDy; dy++)
                    for (int dz = -halfZ; dz <= halfZ; dz++) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.is(Blocks.COBWEB) || state.is(Blocks.VINE)) {
                            level.setBlock(pos, AIR, 2);
                        }
                    }
        }

        /** Тёмно-красное пятно на полу ("кровь"). */
        BlockState bloodFloor() {
            return random.nextFloat() < 0.6F
                    ? Blocks.NETHER_WART_BLOCK.defaultBlockState()
                    : Blocks.RED_TERRACOTTA.defaultBlockState();
        }

        /** Убирает кровавые ковры (после починки). */
        void cleanBlood(int halfX, int halfZ, int minDy, int maxDy) {
            for (int dx = -halfX; dx <= halfX; dx++)
                for (int dy = minDy; dy <= maxDy; dy++)
                    for (int dz = -halfZ; dz <= halfZ; dz++) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        if (level.getBlockState(pos).is(Blocks.RED_CARPET)) {
                            level.setBlock(pos, AIR, 2);
                        }
                    }
        }

        /** Расчищает площадку от деревьев и травы и подсыпает землю под будущий пол. */
        void prepare(int halfX, int halfZ, int height) {
            if (restored) return;
            for (int dx = -halfX; dx <= halfX; dx++) {
                for (int dz = -halfZ; dz <= halfZ; dz++) {
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

        /** Сундук с якорем, записью рядом и немного хлама. При починке сундуки не трогаем. */
        void chest(int dx, int dy, int dz, Direction facing, ItemStack anchor, ItemStack note) {
            if (restored) return;

            BlockPos pos = origin.offset(dx, dy, dz);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), 2);

            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                chest.setItem(13, anchor);
                chest.setItem(12, note);

                Item[] junk = {Items.ROTTEN_FLESH, Items.BONE, Items.STICK, Items.PAPER};
                for (int i = 0; i < 3; i++) {
                    int slot = random.nextInt(27);
                    if (slot == 12 || slot == 13) continue;
                    chest.setItem(slot, new ItemStack(junk[random.nextInt(junk.length)], 1 + random.nextInt(3)));
                }
                chest.setChanged();
            }
        }

        // =====================================================================
        // ЗАБРОШЕННЫЙ ДОМ: каменный, с двускатной крышей, крыльцом, окном со ставнями и двумя трубами
        // Размер 9 x 7 (x от -4 до 4, z от -3 до 3), крыльцо на юге (z = 4). Вход на юге.
        // =====================================================================
        void cabin(ItemStack anchor, ItemStack note) {
            prepare(5, 5, 11);

            BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
            BlockState trim = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
            BlockState glass = Blocks.GLASS.defaultBlockState();

            // пол дома и крыльцо
            for (int x = -4; x <= 4; x++) {
                for (int z = -3; z <= 3; z++) {
                    set(x, -1, z, (!restored && random.nextFloat() < 0.2F) ? Blocks.COBBLESTONE.defaultBlockState() : planks);
                }
                BlockState porch = restored ? Blocks.STONE_BRICKS.defaultBlockState()
                        : (random.nextBoolean() ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
                set(x, -1, 4, porch);
            }

            // стены: булыжник со мхом, по углам светлые каменные колонны
            for (int x = -4; x <= 4; x++) {
                for (int z = -3; z <= 3; z++) {
                    if (Math.abs(x) != 4 && Math.abs(z) != 3) continue;
                    boolean corner = Math.abs(x) == 4 && Math.abs(z) == 3;
                    for (int dy = 0; dy <= 2; dy++) {
                        if (corner) ruined(x, dy, z, pickStone(), 0.04F);
                        else ruined(x, dy, z, pickWall(), dy == 2 ? 0.14F : 0.08F);
                    }
                }
            }

            // верхний брус по длинным стенам
            for (int x = -4; x <= 4; x++) {
                ruined(x, 3, -3, log(Blocks.SPRUCE_LOG, Direction.Axis.X), 0.10F);
                ruined(x, 3, 3, log(Blocks.SPRUCE_LOG, Direction.Axis.X), 0.10F);
            }

            // фронтоны (треугольные торцы) под крышей
            for (int sx : new int[]{-4, 4}) {
                for (int z = -2; z <= 2; z++) {
                    int top = (z == 0) ? 5 : 3 + (4 - Math.abs(z)) - 1;
                    for (int dy = 3; dy <= top; dy++) {
                        ruined(sx, dy, z, pickWall(), 0.06F);
                    }
                }
            }

            // двускатная крыша из каменных ступеней (до починки местами дырявая и поросшая)
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (z == 0) {
                        ruined(x, 6, 0, pickStone(), 0.04F);
                        ruined(x, 7, 0, Blocks.STONE_BRICK_SLAB.defaultBlockState(), 0.10F);
                    } else {
                        int dy = 3 + (4 - Math.abs(z));
                        Direction facing = z < 0 ? Direction.SOUTH : Direction.NORTH;
                        Block block = (restored || random.nextFloat() < 0.7F) ? Blocks.STONE_BRICK_STAIRS : Blocks.MOSSY_STONE_BRICK_STAIRS;
                        ruined(x, dy, z, stair(block, facing), 0.08F);
                    }
                }
            }
            if (!restored) {
                // зелёные заплаты мха и листвы на крыше
                for (int i = 0; i < 5; i++) {
                    int px = random.nextInt(7) - 3;
                    int pz = random.nextInt(7) - 3;
                    if (pz == 0) continue;
                    int dy = 3 + (4 - Math.abs(pz));
                    set(px, dy, pz, leaves());
                    set(px + 1, dy, pz, leaves());
                }
            }

            // две кирпичные трубы на торцах
            for (int sx : new int[]{-4, 4}) {
                for (int dy = 0; dy <= 7; dy++) ruined(sx, dy, 0, pickStone(), 0.0F);
                ruined(sx, 8, 0, Blocks.BRICKS.defaultBlockState(), 0.20F);
                ruined(sx, 9, 0, Blocks.STONE_BRICK_WALL.defaultBlockState(), 0.30F);
            }

            // крыльцо: две опоры под свесом крыши, ступень, висячий фонарь
            for (int dy = 0; dy <= 2; dy++) {
                ruined(-4, dy, 4, trim, dy == 2 ? 0.10F : 0.0F);
                ruined(4, dy, 4, trim, dy == 2 ? 0.10F : 0.0F);
            }
            set(2, 0, 4, (restored ? Blocks.STONE_BRICK_SLAB : Blocks.MOSSY_STONE_BRICK_SLAB).defaultBlockState());
            set(3, 0, 4, Blocks.STONE_BRICK_SLAB.defaultBlockState());
            set(3, 2, 4, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));

            // дверной проём (x = 2) с рамой
            for (int dy = 0; dy <= 2; dy++) {
                set(1, dy, 3, trim);
                set(3, dy, 3, trim);
            }
            set(2, 0, 3, AIR);
            set(2, 1, 3, AIR);
            set(2, 2, 3, planks);

            // окно (x = -1 и 0), по бокам ставни, под ним кусты
            for (int x = -1; x <= 0; x++) {
                for (int dy = 1; dy <= 2; dy++) {
                    if (restored) set(x, dy, 3, glass);
                    else set(x, dy, 3, random.nextFloat() < 0.4F ? web() : AIR);
                }
            }
            BlockState shutter = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
                    .setValue(TrapDoorBlock.FACING, Direction.SOUTH)
                    .setValue(TrapDoorBlock.OPEN, true);
            for (int sx : new int[]{-2, 1}) {
                for (int dy = 1; dy <= 2; dy++) ruined(sx, dy, 4, shutter, 0.25F);
            }
            set(-1, 0, 4, leaves());
            set(0, 0, 4, leaves());
            set(0, 1, 4, leaves());

            // бочки у крыльца
            ruined(-3, 0, 4, barrel(), 0.15F);
            ruined(-3, 1, 4, barrel(), 0.15F);
            ruined(-2, 0, 4, barrel(), 0.15F);

            // плющ на западном торце (до починки)
            if (!restored) {
                for (int z = -2; z <= 2; z++)
                    for (int dy = 0; dy <= 3; dy++)
                        if (random.nextFloat() < 0.35F) setIfAir(-5, dy, z, vine(Direction.EAST));
            }

            // внутри: поперечные балки, сундук, стол со стулом, книжные полки, очаг
            for (int bx : new int[]{-2, 2}) {
                for (int z = -2; z <= 2; z++) ruined(bx, 3, z, log(Blocks.SPRUCE_LOG, Direction.Axis.Z), 0.10F);
            }
            chest(-3, 0, -2, Direction.SOUTH, anchor, note);
            set(1, 0, -1, Blocks.SPRUCE_FENCE.defaultBlockState());
            set(1, 1, -1, Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState());
            set(2, 0, -1, stair(Blocks.SPRUCE_STAIRS, Direction.EAST));
            set(-3, 0, 1, Blocks.BOOKSHELF.defaultBlockState());
            set(-3, 1, 1, Blocks.BOOKSHELF.defaultBlockState());
            set(3, 0, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));

            // паутина под потолком (до починки)
            int[][] corners = {{-3, -2}, {3, -2}, {3, 2}, {-3, 2}};
            for (int[] c : corners) {
                if (!restored && random.nextFloat() < 0.7F) set(c[0], 3, c[1], web());
            }

            if (restored) cleanDecay(5, 5, 0, 10);
        }

        // =====================================================================
        // РАЗВАЛИНЫ С ПОДВАЛОМ: сверху низкая ограда и деревянный пол с лазом,
        // внизу большой подвал 7x7 с бочками, ящиками, полками с фонарями, столом с горшком.
        // =====================================================================
        void cellar(ItemStack anchor, ItemStack note) {
            prepare(5, 5, 6);

            BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();

            // каменная коробка 9x9 под землёй (dy от -6 до -1); комнату 7x7 внутри не трогаем
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    for (int dy = -6; dy <= -1; dy++) {
                        boolean interior = Math.abs(x) <= 3 && Math.abs(z) <= 3 && dy >= -5 && dy <= -2;
                        if (interior) continue;
                        set(x, dy, z, dy == -6 ? pickCellarFloor() : pickStone());
                    }
                }
            }

            // при постройке вырезаем внутри комнату
            if (!restored) {
                fill(-3, -5, -3, 3, -2, 3, AIR);
            }

            // потолок = деревянный пол развалин (до починки местами прогнил)
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    boolean overRoom = Math.abs(x) <= 3 && Math.abs(z) <= 3;
                    if (!restored && overRoom && random.nextFloat() < 0.08F) set(x, -1, z, AIR);
                    else set(x, -1, z, planks);
                }
            }

            // лаз с лестницей у северной стены
            set(0, -1, -3, AIR);
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
            for (int dy = -5; dy <= -1; dy++) {
                set(0, dy, -3, ladder);
            }

            // потолочные балки и угловые колонны
            for (int z : new int[]{-1, 1}) {
                for (int x = -3; x <= 3; x++) ruined(x, -2, z, log(Blocks.SPRUCE_LOG, Direction.Axis.X), 0.05F);
            }
            int[][] pillars = {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}};
            for (int[] p : pillars) {
                for (int dy = -5; dy <= -2; dy++) set(p[0], dy, p[1], Blocks.STONE_BRICKS.defaultBlockState());
            }

            // бочки и ящики (штабель у восточной стены, ящики в юго-западном углу)
            ruined(3, -5, -1, barrel(), 0.10F);
            ruined(3, -5, 0, barrel(), 0.10F);
            ruined(3, -5, 1, barrel(), 0.10F);
            ruined(3, -4, 0, barrel(), 0.10F);
            ruined(-3, -5, 2, planks, 0.10F);
            ruined(-3, -4, 2, planks, 0.10F);
            ruined(-2, -5, 2, barrel(), 0.10F);

            // стол: бочка с цветочным горшком
            set(-3, -5, -1, barrel());
            set(-3, -4, -1, Blocks.POTTED_FERN.defaultBlockState());

            // полки с фонарями
            set(-3, -3, 1, slabTop(Blocks.SPRUCE_SLAB));
            set(-3, -3, 2, slabTop(Blocks.SPRUCE_SLAB));
            set(-3, -2, 1, Blocks.LANTERN.defaultBlockState());
            set(-1, -3, 3, slabTop(Blocks.SPRUCE_SLAB));
            set(0, -3, 3, slabTop(Blocks.SPRUCE_SLAB));
            set(0, -2, 3, Blocks.LANTERN.defaultBlockState());

            // сундук с якорем у южной стены, свечи на полу
            chest(0, -5, 3, Direction.NORTH, anchor, note);
            set(2, -5, -2, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
            set(-2, -5, -2, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));

            // паутина и плющ (до починки)
            if (!restored) {
                int[][] webSpots = {{-2, -2}, {2, -2}, {2, 2}, {-2, 2}};
                for (int[] c : webSpots) {
                    if (random.nextFloat() < 0.8F) set(c[0], -2, c[1], web());
                }
                setIfAir(1, -5, 2, web());
                setIfAir(-1, -5, -2, web());
                for (int x = -3; x <= 3; x++) {
                    if (x == 0) continue;
                    for (int dy = -5; dy <= -2; dy++)
                        if (random.nextFloat() < 0.10F) setIfAir(x, dy, -3, vine(Direction.NORTH));
                }
                for (int z = -2; z <= 3; z++)
                    for (int dy = -5; dy <= -2; dy++)
                        if (random.nextFloat() < 0.08F) setIfAir(-3, dy, z, vine(Direction.WEST));
            }

            // низкая ограда по краю площадки (до починки разрушена) и бочки наверху
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                    ruined(x, 0, z, pickStone(), 0.25F);
                    ruined(x, 1, z, pickStone(), 0.60F);
                }
            }
            ruined(3, 0, 2, barrel(), 0.30F);
            ruined(3, 0, 3, barrel(), 0.30F);

            if (restored) cleanDecay(5, 5, -6, 2);
        }

        // =====================================================================
        // КАМЕННЫЕ РУИНЫ с алтарём в центре и тремя свечами перед ним
        // =====================================================================
        void ruins(ItemStack anchor, ItemStack note) {
            prepare(4, 4, 6);

            // пол: каменные плиты (до починки вперемешку с землёй)
            for (int x = -4; x <= 4; x++)
                for (int z = -4; z <= 4; z++)
                    if (restored || random.nextFloat() > 0.15F) set(x, -1, z, pickStone());

            // стены по краю: обломки, а после починки целые (с проходом с юга)
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (Math.abs(x) != 4 && Math.abs(z) != 4) continue;
                    int height;
                    if (restored) height = (z == 4 && Math.abs(x) <= 1) ? 0 : 3;
                    else height = random.nextInt(4); // 0..3
                    for (int dy = 0; dy < height; dy++) set(x, dy, z, pickStone());
                }
            }

            // колонны (до починки обломанные и во мху)
            int[][] pillars = {{-4, -4}, {4, -4}, {-4, 4}, {4, 4}, {0, -4}, {-4, 0}, {4, 0}};
            for (int[] p : pillars) {
                int height = restored ? 5 : 3 + random.nextInt(3); // 3..5
                BlockState block = restored ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
                for (int dy = 0; dy < height; dy++) set(p[0], dy, p[1], block);
                if (!restored && random.nextFloat() < 0.6F) {
                    int wx = p[0] + (p[0] == 0 ? 0 : (p[0] > 0 ? -1 : 1));
                    int wz = p[1] + (p[1] == 0 ? 0 : (p[1] > 0 ? -1 : 1));
                    set(wx, height - 1, wz, web());
                }
            }

            // помост из тёмного камня вокруг алтаря
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    set(dx, -1, dz, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            for (int dx = -2; dx <= 2; dx++) {
                set(dx, -1, 2, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
            }

            // алтарь в центре, на нём сундук
            set(0, 0, 0, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            chest(0, 1, 0, Direction.SOUTH, anchor, note);

            // большой крест за алтарём из тёмного дерева, к нему подвешены синие фонари на цепях
            BlockState crossLog = log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y);
            BlockState crossArm = log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.X);
            set(0, 0, -3, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
            for (int dy = 1; dy <= 5; dy++) set(0, dy, -3, crossLog);
            for (int dx = -2; dx <= 2; dx++) {
                if (dx != 0) set(dx, 4, -3, crossArm);
            }
            for (int dx = -2; dx <= 2; dx += 4) {
                set(dx, 3, -3, Blocks.CHAIN.defaultBlockState());
                set(dx, 2, -3, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }

            // синие огни: фонари по бокам алтаря и два костра душ у основания креста
            set(-2, 0, 0, Blocks.SOUL_LANTERN.defaultBlockState());
            set(2, 0, 0, Blocks.SOUL_LANTERN.defaultBlockState());
            set(-3, 0, -2, Blocks.SOUL_CAMPFIRE.defaultBlockState());
            set(3, 0, -2, Blocks.SOUL_CAMPFIRE.defaultBlockState());

            // пустая рама на кресте над алтарём: в неё нужно вставить перевёрнутый крест
            if (!restored) {
                ItemFrame frame = new ItemFrame(level, origin.offset(0, 2, -2), Direction.SOUTH);
                frame.setInvulnerable(true);
                frame.getPersistentData().putBoolean(TAG_ALTAR_FRAME, true);
                level.addFreshEntity(frame);
            }

            // три свечи перед алтарём: на ритуале зажигаются по одной (при починке не трогаем: они уже горят)
            if (!restored) {
                for (int dx = -1; dx <= 1; dx++) {
                    set(dx, 0, 1, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, false));
                }
            }

            // кровь: лужа у подножия креста, следы от входа к алтарю, пятна и обрывки красной ткани
            if (!restored) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -3; dz <= -1; dz++) {
                        if (random.nextFloat() < 0.55F) set(dx, -1, dz, bloodFloor());
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 4; dz++) {
                        if (random.nextFloat() < 0.45F) set(dx, -1, dz, bloodFloor());
                        if (dz != 1 && random.nextFloat() < 0.25F) setIfAir(dx, 0, dz, Blocks.RED_CARPET.defaultBlockState());
                    }
                }
                for (int i = 0; i < 6; i++) {
                    int dx = random.nextInt(7) - 3;
                    int dz = random.nextInt(7) - 3;
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                    set(dx, -1, dz, bloodFloor());
                }
                // подтёки на ступени и на основании креста
                set(0, 1, -3, Blocks.RED_TERRACOTTA.defaultBlockState());
                set(0, 2, -3, random.nextBoolean() ? Blocks.RED_TERRACOTTA.defaultBlockState() : crossLog);
            }

            // после ритуала у алтаря расцветают цветы
            if (restored) {
                BlockState[] flowers = {
                        Blocks.OXEYE_DAISY.defaultBlockState(),
                        Blocks.LILY_OF_THE_VALLEY.defaultBlockState(),
                        Blocks.AZURE_BLUET.defaultBlockState(),
                        Blocks.POPPY.defaultBlockState()};
                int[][] flowerSpots = {{-3, 1}, {3, 1}, {-3, -1}, {3, -1}, {-2, 3}, {2, 3}, {-1, 3}, {1, 3}};
                for (int i = 0; i < flowerSpots.length; i++) {
                    setIfAir(flowerSpots[i][0], 0, flowerSpots[i][1], flowers[i % flowers.length]);
                }
            }

            if (restored) {
                cleanDecay(4, 4, 0, 6);
                cleanBlood(4, 4, 0, 1);
            }
        }
    }
}
