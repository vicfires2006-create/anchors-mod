package com.anchors;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Жуткие глюки при Угасании. Почти все они "поддельные": сервер посылает одному игроку ложные блоки,
 * а потом посылает настоящие обратно. Сам мир при этом НЕ портится (даже если игра вылетит).
 *
 *   стадия 2: куски мира превращаются в "сломанную текстуру" (фиолетово-чёрная шахматка)
 *   стадия 3: ещё кровь на земле, ямы, дома и стены то ломаются, то пропадают; изредка экран замирает
 *   стадия 4: всё это намного чаще, дома пропадают целиком, заморозки чаще
 */
public final class GlitchEffects {

    // ---------- настройки: шансы на каждую секунду, в процентах (по стадиям 2, 3, 4) ----------
    private static final int[] CHANCE_GLITCH = {4, 10, 18};   // сломанные текстуры
    private static final int[] CHANCE_BLOOD = {0, 6, 12};     // кровь на земле
    private static final int[] CHANCE_HOLE = {0, 4, 9};       // ямы
    private static final int[] CHANCE_BUILDING = {0, 3, 7};   // дома ломаются или пропадают
    /** Шанс заморозки за секунду, в промилле (по стадиям 3 и 4). Между заморозками минимум 90 секунд. */
    private static final int[] CHANCE_FREEZE_PERMILLE = {10, 25};
    private static final int FREEZE_COOLDOWN_TICKS = 20 * 90;

    /** Настоящее удаление деревьев и построек (шанс за секунду в промилле по стадиям 2, 3, 4). */
    private static final int[] CHANCE_DELETE_PERMILLE = {0, 15, 40};
    /** Сколько блоков глюки могут удалить за весь мир. После ритуала все они возвращаются. */
    private static final int MAX_REMOVED_BLOCKS = 20000;
    /** Рядом с постройками с якорями (в блоках) ничего не удаляется. */
    private static final int PROTECT_RADIUS = 16;

    /** Не больше стольких поддельных блоков у одного игрока одновременно. */
    private static final int MAX_FAKE_BLOCKS = 1500;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private record Restore(BlockPos pos, int dueTick) {}
    private record Freeze(double x, double y, double z, float yaw, float pitch, int endTick) {}

    private static final Map<UUID, List<Restore>> PENDING = new HashMap<>();
    private static final Map<UUID, Freeze> FREEZES = new HashMap<>();
    private static final Map<UUID, Integer> FREEZE_READY_AT = new HashMap<>();

    private GlitchEffects() {
    }

    // =====================================================================
    // Раз в секунду для каждого игрока
    // =====================================================================
    public static void secondTick(ServerPlayer player, ServerLevel level, int stage) {
        if (stage < 2) return;
        if (level.dimension() != Level.OVERWORLD) return;

        RandomSource random = level.random;
        int tick = level.getServer().getTickCount();
        int index = Math.min(stage, 4) - 2; // 0, 1 или 2

        if (random.nextInt(100) < CHANCE_GLITCH[index]) glitchCluster(player, level, tick);
        if (random.nextInt(100) < CHANCE_BLOOD[index]) bloodSplatter(player, level, tick);
        if (random.nextInt(100) < CHANCE_HOLE[index]) groundHole(player, level, tick);
        if (random.nextInt(100) < CHANCE_BUILDING[index]) buildingGlitch(player, level, tick, stage >= 4);
        if (stage >= 3) tryFreeze(player, level, tick, stage);
        if (random.nextInt(1000) < CHANCE_DELETE_PERMILLE[index]) deleteTreeOrBuilding(player, level);
    }

    // =====================================================================
    // Раз в тик для всего сервера: возвращает настоящие блоки и держит замороженных
    // =====================================================================
    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();

        if (now % 5 == 0 && !PENDING.isEmpty()) {
            Iterator<Map.Entry<UUID, List<Restore>>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, List<Restore>> entry = it.next();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    it.remove();
                    continue;
                }
                List<Restore> list = entry.getValue();
                list.removeIf(restore -> {
                    if (restore.dueTick() > now) return false;
                    player.connection.send(new ClientboundBlockUpdatePacket(player.level(), restore.pos()));
                    return true;
                });
                if (list.isEmpty()) it.remove();
            }
        }

        if (!FREEZES.isEmpty()) {
            Iterator<Map.Entry<UUID, Freeze>> it = FREEZES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Freeze> entry = it.next();
                Freeze freeze = entry.getValue();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null || now >= freeze.endTick() || player.isPassenger()) {
                    it.remove();
                    continue;
                }
                if (now % 2 == 0) {
                    player.connection.teleport(freeze.x(), freeze.y(), freeze.z(), freeze.yaw(), freeze.pitch());
                }
            }
        }
    }

    public static void clearPlayer(UUID id) {
        PENDING.remove(id);
        FREEZES.remove(id);
        FREEZE_READY_AT.remove(id);
    }

    public static void clearAll() {
        PENDING.clear();
        FREEZES.clear();
        FREEZE_READY_AT.clear();
    }

    // =====================================================================
    // Отдельные глюки
    // =====================================================================

    /** Кусок мира рядом превращается в "сломанную текстуру": фиолетово-чёрная шахматка. */
    private static void glitchCluster(ServerPlayer player, ServerLevel level, int tick) {
        RandomSource random = level.random;
        BlockPos center = randomSurface(player, level, 4, 14);
        if (center == null) return;

        int duration = 40 + random.nextInt(80);
        for (int i = 0; i < 10; i++) {
            BlockPos pos = center.offset(random.nextInt(3) - 1, random.nextInt(3) - 1, random.nextInt(3) - 1);
            fake(player, level, pos, checker(pos), duration, tick);
        }
        playNotify(player, "block.note_block.bit", 0.5F, 0.5F + random.nextFloat() * 0.5F);
    }

    /** Лужа крови на земле и красные брызги. */
    private static void bloodSplatter(ServerPlayer player, ServerLevel level, int tick) {
        RandomSource random = level.random;
        BlockPos center = randomSurface(player, level, 4, 12);
        if (center == null) return;

        int duration = 120 + random.nextInt(120);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 4 || random.nextFloat() > 0.75F) continue;
                BlockPos ground = surfaceAt(level, center.getX() + dx, center.getZ() + dz);
                if (ground == null) continue;
                BlockState blood = random.nextFloat() < 0.6F
                        ? Blocks.NETHER_WART_BLOCK.defaultBlockState()
                        : Blocks.RED_TERRACOTTA.defaultBlockState();
                fake(player, level, ground, blood, duration, tick);
            }
        }
        DustParticleOptions red = new DustParticleOptions(new Vector3f(0.6F, 0.0F, 0.0F), 1.5F);
        level.sendParticles(player, red, false, center.getX() + 0.5, center.getY() + 1.2, center.getZ() + 0.5,
                25, 0.9, 0.5, 0.9, 0.0);
        playNotify(player, "entity.warden.heartbeat", 0.7F, 0.7F);
    }

    /** Внезапная яма 2x2 глубиной в три блока. */
    private static void groundHole(ServerPlayer player, ServerLevel level, int tick) {
        RandomSource random = level.random;
        BlockPos center = randomSurface(player, level, 5, 12);
        if (center == null) return;

        int duration = 60 + random.nextInt(80);
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos ground = surfaceAt(level, center.getX() + dx, center.getZ() + dz);
                if (ground == null) continue;
                for (int dy = 0; dy < 3; dy++) {
                    fake(player, level, ground.below(dy), AIR, duration, tick);
                }
            }
        }
        playNotify(player, "block.sculk.break", 0.8F, 0.6F);
    }

    /**
     * Ищет рукотворные блоки (доски, камень, стёкла, двери...) в месте метрах в 8-30 от игрока.
     * Если там что-то построено, постройка то ломается дырами и "сломанной текстурой", то пропадает целиком.
     */
    private static void buildingGlitch(ServerPlayer player, ServerLevel level, int tick, boolean strong) {
        RandomSource random = level.random;
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 8 + random.nextDouble() * 22;
        int cx = (int) Math.floor(player.getX() + Math.cos(angle) * dist);
        int cz = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
        int cy = player.blockPosition().getY();

        List<BlockPos> manMade = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -3; dy <= 9; dy++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!level.hasChunkAt(pos)) continue;
                    if (isManMade(level.getBlockState(pos))) manMade.add(pos);
                }
            }
        }
        if (manMade.size() < 12) return; // здесь ничего не построено

        boolean vanish = strong && random.nextBoolean();
        int duration = (vanish ? 100 : 80) + random.nextInt(100);

        for (BlockPos pos : manMade) {
            if (vanish) {
                fake(player, level, pos, AIR, duration, tick);
            } else if (random.nextFloat() < 0.45F) {
                fake(player, level, pos, random.nextBoolean() ? AIR : checker(pos), duration, tick);
            }
        }
        playNotify(player, "block.stone.break", 0.9F, 0.5F);
    }

    /** Экран замирает: игрок на несколько секунд не может ни двигаться, ни повернуть голову. */
    private static void tryFreeze(ServerPlayer player, ServerLevel level, int tick, int stage) {
        UUID id = player.getUUID();
        if (FREEZES.containsKey(id)) return;
        if (tick < FREEZE_READY_AT.getOrDefault(id, 0)) return;
        if (player.fallDistance > 0.0F || player.isPassenger() || player.getAbilities().flying) return;

        int permille = CHANCE_FREEZE_PERMILLE[stage >= 4 ? 1 : 0];
        if (level.random.nextInt(1000) >= permille) return;

        int duration = 50 + level.random.nextInt(50); // 2.5-5 секунд
        FREEZES.put(id, new Freeze(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), tick + duration));
        FREEZE_READY_AT.put(id, tick + duration + FREEZE_COOLDOWN_TICKS);

        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 6, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.JUMP, duration, 128, false, false, false));
        playNotify(player, "block.beacon.deactivate", 0.8F, 0.5F);
        playNotify(player, "entity.warden.heartbeat", 1.0F, 0.6F);
    }

    /**
     * По-настоящему удаляет дерево или рукотворную постройку метрах в 10-35 от игрока.
     * Каждый удалённый блок запоминается, и после ритуала всё возвращается на место.
     * Постройки с якорями и всё рядом с ними не трогаем.
     */
    private static void deleteTreeOrBuilding(ServerPlayer player, ServerLevel level) {
        FadingData data = FadingData.get(level.getServer());
        if (data.getRemovedCount() >= MAX_REMOVED_BLOCKS) return;

        boolean tree = level.random.nextBoolean();
        Predicate<BlockState> seedTest = tree ? state -> state.is(BlockTags.LOGS) : GlitchEffects::isManMade;
        Predicate<BlockState> bodyTest = tree
                ? state -> state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                : GlitchEffects::isManMade;

        BlockPos seed = findSeed(player, level, seedTest);
        if (seed == null) return;

        Set<BlockPos> blocks = floodFill(level, seed, bodyTest, tree ? 500 : 700);
        if (blocks.size() < (tree ? 6 : 12)) return;

        // ничего не удаляем рядом с постройками с якорями
        for (BlockPos pos : blocks) {
            if (nearModStructure(data, pos)) return;
        }

        for (BlockPos pos : blocks) {
            data.addRemoved(pos, level.getBlockState(pos));
            level.setBlock(pos, AIR, 2);
        }

        DustParticleOptions dust = new DustParticleOptions(new Vector3f(0.15F, 0.0F, 0.15F), 2.0F);
        level.sendParticles(player, dust, false, seed.getX() + 0.5, seed.getY() + 1.0, seed.getZ() + 0.5,
                40, 2.0, 2.0, 2.0, 0.0);
        playNotify(player, "block.sculk.shriek", 0.4F, 0.6F);
    }

    /** Ищет подходящий блок (бревно или часть постройки) на расстоянии 10-35 блоков от игрока. */
    private static BlockPos findSeed(ServerPlayer player, ServerLevel level, Predicate<BlockState> test) {
        RandomSource random = level.random;
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 10 + random.nextDouble() * 25;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
            BlockPos ground = surfaceAt(level, x, z);
            if (ground == null) continue;
            for (int dy = -3; dy <= 12; dy++) {
                BlockPos pos = ground.above(dy);
                if (level.hasChunkAt(pos) && test.test(level.getBlockState(pos))) return pos;
            }
        }
        return null;
    }

    /** Собирает связанные блоки (в том числе по диагонали), пока не наберётся limit. */
    private static Set<BlockPos> floodFill(ServerLevel level, BlockPos start, Predicate<BlockState> test, int limit) {
        Set<BlockPos> found = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        found.add(start);
        queue.add(start);
        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.offset(dx, dy, dz);
                        if (found.contains(next) || !level.hasChunkAt(next)) continue;
                        if (!test.test(level.getBlockState(next))) continue;
                        found.add(next);
                        queue.add(next);
                        if (found.size() >= limit) return found;
                    }
                }
            }
        }
        return found;
    }

    private static boolean nearModStructure(FadingData data, BlockPos pos) {
        for (FadingData.StructureRecord record : data.getStructures()) {
            BlockPos origin = record.pos();
            if (Math.abs(origin.getX() - pos.getX()) <= PROTECT_RADIUS
                    && Math.abs(origin.getZ() - pos.getZ()) <= PROTECT_RADIUS) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Помощники
    // =====================================================================

    /** Посылает игроку ложный блок и запоминает, когда вернуть настоящий. Блоки с данными (сундуки, таблички) не трогаем. */
    private static void fake(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, int duration, int tick) {
        if (!level.hasChunkAt(pos)) return;
        BlockState real = level.getBlockState(pos);
        if (real.isAir() && state.isAir()) return;
        if (real.hasBlockEntity()) return;

        List<Restore> list = PENDING.computeIfAbsent(player.getUUID(), id -> new ArrayList<>());
        if (list.size() >= MAX_FAKE_BLOCKS) return;

        player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
        list.add(new Restore(pos.immutable(), tick + duration));
    }

    /** Блок с настоящей "сломанной текстурой" (картинка лежит в textures/item/glitch.png). */
    private static BlockState checker(BlockPos pos) {
        return ModBlocks.GLITCH_BLOCK.get().defaultBlockState();
    }

    private static boolean isManMade(BlockState state) {
        if (state.isAir() || state.hasBlockEntity()) return false;
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.STONE_BRICKS)
                || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.SLABS)
                || state.is(BlockTags.WALLS)
                || state.is(BlockTags.FENCES)
                || state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.WOOL)
                || state.is(Blocks.GLASS)
                || state.is(Blocks.GLASS_PANE)
                || state.is(Blocks.BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.BOOKSHELF)
                || state.is(Blocks.LADDER);
    }

    /** Верхний твёрдый блок земли в точке (или null, если кусок мира не загружен или там вода). */
    private static BlockPos surfaceAt(ServerLevel level, int x, int z) {
        BlockPos probe = new BlockPos(x, level.getMinBuildHeight() + 1, z);
        if (!level.hasChunkAt(probe)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (y <= level.getMinBuildHeight()) return null;
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getFluidState(pos).isEmpty()) return null;
        if (level.getBlockState(pos).isAir()) return null;
        return pos;
    }

    /** Случайная точка на земле на расстоянии от minDist до maxDist (по горизонтали) от игрока. */
    private static BlockPos randomSurface(ServerPlayer player, ServerLevel level, int minDist, int maxDist) {
        RandomSource random = level.random;
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minDist + random.nextDouble() * (maxDist - minDist);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * dist);
            BlockPos ground = surfaceAt(level, x, z);
            if (ground != null) return ground;
        }
        return null;
    }

    private static void playNotify(ServerPlayer player, String soundId, float volume, float pitch) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        if (sound != null) {
            player.playNotifySound(sound, SoundSource.AMBIENT, volume, pitch);
        }
    }
}
