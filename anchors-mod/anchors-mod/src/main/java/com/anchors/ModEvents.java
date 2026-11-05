package com.anchors;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = AnchorsMod.MOD_ID)
public class ModEvents {

    /** Сколько "Угасания" добавляется за каждую секунду с включённым предметом. */
    private static final double FADING_PER_SECOND_LANTERN = 1.0;
    private static final double FADING_PER_SECOND_MUSIC_BOX = 1.0;

    /** Мелодия шкатулки: числа это ноты (12 = базовая), -1 = пауза. Одна нота раз в NOTE_INTERVAL тиков. */
    private static final int NOTE_INTERVAL = 8;
    private static final int[] MELODY = {12, 15, 19, 17, 15, 12, 10, 12, 15, 19, 22, 20, 19, 15, 17, -1};

    /** Тихий мотив фонаря: "кто-то ищет дорогу домой". Одна нота раз в LANTERN_NOTE_INTERVAL тиков. */
    private static final int LANTERN_NOTE_INTERVAL = 30;
    private static final int[] LANTERN_MELODY = {12, 10, 7, -1, 10, 7, 3, -1};

    /** Сколько "Угасания" мир забирает, когда кукла принимает смертельный удар. */
    private static final double FADING_PER_DOLL_SAVE = 60.0;

    /** Метка "этому игроку уже ставили сундук с дневником" (хранится вместе с игроком). */
    private static final String TAG_DIARY_GIVEN = "anchors_diary_given";

    /**
     * Сколько Угасания мир набирает сам каждую секунду после того, как кто-то впервые взял якорь.
     * 0.4 в секунду = примерно 40 минут до четвёртой стадии (приход Коллекционера), если не пользоваться якорями.
     */
    private static final double PASSIVE_FADING_PER_SECOND = 0.4;

    /**
     * Когда (в секундах игры в обычном мире) появляется очередная постройка:
     * первая (фонарь), вторая (шкатулка), третья (кукла). Меняй числа как хочешь.
     */
    private static final int[] HOUSE_DELAYS_SECONDS = {300, 720, 1200};
    private static final String TAG_PLAY_SECONDS = "anchors_play_seconds";
    private static final String TAG_HOUSES_BUILT = "anchors_houses_built";

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        ServerLevel level = player.serverLevel();
        boolean lantern = OldLanternItem.holdsActive(player);
        boolean box = MusicBoxItem.holdsActive(player);

        // Шкатулка: играет мелодию и успокаивает монстров рядом
        if (box) {
            if (player.tickCount % NOTE_INTERVAL == 0) {
                playMusicBoxNote(player, level);
            }
            if (player.tickCount % 5 == 0) {
                calmMonsters(player, level);
            }
        }

        // Фонарь: тихий мотив
        if (lantern && player.tickCount % LANTERN_NOTE_INTERVAL == 0) {
            int note = LANTERN_MELODY[(player.tickCount / LANTERN_NOTE_INTERVAL) % LANTERN_MELODY.length];
            if (note >= 0) {
                float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
                playAt(level, player.getX(), player.getY() + 1.0, player.getZ(), "block.note_block.flute", 0.45F, pitch);
            }
        }

        if (player.tickCount % 20 != 0) return; // дальше раз в секунду

        trackHouses(player, level);

        FadingData data = FadingData.get(level.getServer());

        // Ритуал завершён: Угасания больше нет. Тихий невидимый эффект сообщает клиенту,
        // что тёмные ночи не нужны, а оригинальная музыка может вернуться.
        if (data.isRitualDone()) {
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 100, 0, false, false, false));
            if (lantern) {
                useLanternPower(player, level);
            }
            return;
        }

        double cost = 0;
        if (lantern) cost += FADING_PER_SECOND_LANTERN;
        if (box) cost += FADING_PER_SECOND_MUSIC_BOX;

        if (cost > 0) {
            int before = data.getStage();
            data.add(cost);
            int after = data.getStage();

            if (lantern) {
                useLanternPower(player, level);
            }
            if (after > before) {
                player.displayClientMessage(Component.translatable("message.anchors.stage_" + after), true);
            }
        }

        applyAtmosphere(player, level, data.getStage());
    }

    /** Номер якоря в порядке ритуала: 0 фонарь, 1 шкатулка, 2 кукла, -1 это не якорь. */
    private static int anchorKind(ItemStack stack) {
        if (stack.getItem() instanceof OldLanternItem) return 0;
        if (stack.getItem() instanceof MusicBoxItem) return 1;
        if (stack.getItem() instanceof RagDollItem) return 2;
        return -1;
    }

    private static boolean hasAnyAnchor(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (anchorKind(stack) >= 0) return true;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (anchorKind(stack) >= 0) return true;
        }
        return false;
    }

    /** Раз в секунду на сервере: включает таймер, когда кто-то впервые взял якорь, и сам растит Угасание. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 20 != 0) return;

        FadingData data = FadingData.get(server);
        if (data.isRitualDone()) return;

        if (!data.isTimerStarted()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (hasAnyAnchor(player)) {
                    data.startTimer();
                    break;
                }
            }
            return;
        }

        int before = data.getStage();
        data.add(PASSIVE_FADING_PER_SECOND);
        int after = data.getStage();
        if (after > before) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.displayClientMessage(Component.translatable("message.anchors.stage_" + after), true);
            }
        }
    }

    /** Ритуал: правый клик якорем по алтарю (или по свечам перед ним). Порядок: фонарь, шкатулка, кукла. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ServerLevel level = player.serverLevel();
        FadingData data = FadingData.get(level.getServer());
        BlockPos altar = data.getAltarPos();
        if (altar == null || data.isRitualDone()) return;

        BlockPos clicked = event.getPos();
        boolean onAltar = clicked.equals(altar)
                || (clicked.getY() == altar.getY()
                    && clicked.getZ() == altar.getZ() + 1
                    && Math.abs(clicked.getX() - altar.getX()) <= 1);
        if (!onAltar) return;

        ItemStack held = player.getMainHandItem();
        int kind = anchorKind(held);
        if (kind < 0) return; // не якорь: ничего особенного не происходит

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        int progress = data.getRitualProgress();
        double ax = altar.getX() + 0.5;
        double ay = altar.getY() + 1.0;
        double az = altar.getZ() + 0.5;

        if (kind != progress) {
            player.displayClientMessage(Component.translatable("message.anchors.ritual_wrong"), true);
            playAt(level, ax, ay, az, "block.candle.extinguish", 1.0F, 0.8F);
            return;
        }

        held.shrink(1);

        // зажигаем очередную свечу перед алтарём
        BlockPos candle = altar.offset(-1 + progress, 0, 1);
        level.setBlock(candle, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true), 3);

        level.sendParticles(ParticleTypes.SOUL, ax, ay + 0.2, az, 14, 0.3, 0.4, 0.3, 0.02);
        float[] pitches = {0.8F, 1.0F, 1.3F};
        playAt(level, ax, ay, az, "block.note_block.bell", 1.0F, pitches[progress]);

        data.setRitualProgress(progress + 1);
        if (progress + 1 >= 3) {
            completeRitual(level, player, data);
        } else {
            player.displayClientMessage(Component.translatable("message.anchors.ritual_step_" + (progress + 1)), true);
        }
    }

    /** Хорошая концовка: души свободны, постройки целы, Угасание исчезает. */
    private static void completeRitual(ServerLevel level, ServerPlayer player, FadingData data) {
        data.setRitualDone(true);
        data.set(0);

        BlockPos altar = data.getAltarPos();
        double ax = altar.getX() + 0.5;
        double az = altar.getZ() + 0.5;

        // души поднимаются над алтарём
        for (int i = 0; i < 8; i++) {
            double y = altar.getY() + 1.0 + i * 0.7;
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, ax, y, az, 20, 0.4, 0.3, 0.4, 0.03);
            level.sendParticles(ParticleTypes.SOUL, ax, y, az, 10, 0.4, 0.3, 0.4, 0.05);
        }

        // аккорд колокольчиков и далёкий гул
        float[] chord = {0.7F, 0.84F, 1.0F, 1.26F, 1.5F};
        for (float pitch : chord) {
            playAt(level, ax, altar.getY() + 1.0, az, "block.note_block.bell", 1.0F, pitch);
        }
        playAt(level, ax, altar.getY() + 1.0, az, "block.beacon.activate", 1.0F, 0.8F);

        // чиним все постройки
        for (FadingData.StructureRecord record : data.getStructures()) {
            AbandonedStructures.restore(level, record.pos(), AbandonedStructures.Kind.values()[record.kind()]);
        }

        // надпись на экране у всех игроков
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(20, 100, 40));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("message.anchors.ritual_subtitle")));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("message.anchors.ritual_title")));
        }

        // последняя запись дневника достаётся тому, кто завершил ритуал
        ItemStack last = DiaryBook.entry(4);
        if (!player.getInventory().add(last)) {
            player.drop(last, false);
        }
    }

    /** Считает время игры и, когда приходит срок, строит рядом заброшенную постройку с якорем. */
    private static void trackHouses(ServerPlayer player, ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;

        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        int built = persisted.getInt(TAG_HOUSES_BUILT);
        if (built >= HOUSE_DELAYS_SECONDS.length) return;

        int seconds = persisted.getInt(TAG_PLAY_SECONDS) + 1;
        persisted.putInt(TAG_PLAY_SECONDS, seconds);

        if (seconds >= HOUSE_DELAYS_SECONDS[built]) {
            BlockPos where = AbandonedStructures.spawnNear(player, AbandonedStructures.Kind.values()[built]);
            if (where != null) {
                persisted.putInt(TAG_HOUSES_BUILT, built + 1);
                player.displayClientMessage(Component.translatable("message.anchors.house_hint"), true);
                playAt(level, player.getX(), player.getY(), player.getZ(), "ambient.cave", 0.9F, 0.5F);
            } else {
                // подходящего места не нашлось: попробуем снова через 20 секунд
                persisted.putInt(TAG_PLAY_SECONDS, HOUSE_DELAYS_SECONDS[built] - 20);
            }
        }
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** Команда /house 1, 2 или 3: сразу построить постройку (1 дом, 2 подвал, 3 руины) и показать координаты. */
    @SubscribeEvent
    public static void onRegisterHouseCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("house")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("number", IntegerArgumentType.integer(1, 3)).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    int number = IntegerArgumentType.getInteger(ctx, "number");
                    BlockPos where = AbandonedStructures.spawnNear(player, AbandonedStructures.Kind.values()[number - 1]);
                    if (where == null) {
                        ctx.getSource().sendFailure(Component.translatable("command.anchors.house.fail"));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                            "command.anchors.house.ok", where.getX(), where.getY(), where.getZ()), false);
                    return 1;
                })));
    }

    /** Первый раз в этом мире: рядом с игроком появляется сундук с дневником. */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (persisted.getBoolean(TAG_DIARY_GIVEN)) return;

        persisted.putBoolean(TAG_DIARY_GIVEN, true);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);

        DiaryChest.spawnNear(player);
    }

    /** Команда /diary: поставить ещё один сундук с дневником рядом с собой (для проверки). */
    @SubscribeEvent
    public static void onRegisterDiaryCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("diary")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    DiaryChest.spawnNear(ctx.getSource().getPlayerOrException());
                    return 1;
                }));
    }

    /** Кукла принимает смертельный удар вместо игрока. */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return; // пустота, /kill
        if (event.getAmount() < player.getHealth()) return; // удар не смертельный

        ItemStack doll = RagDollItem.find(player);
        if (doll.isEmpty()) return;

        // Отменяем смертельный урон: игрок остаётся жив, но слаб
        event.setCanceled(true);
        player.setHealth(6.0F);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 40, 4));

        ServerLevel level = player.serverLevel();
        FadingData.get(level.getServer()).add(FADING_PER_DOLL_SAVE);

        int cracks = RagDollItem.getCracks(doll) + 1;
        if (cracks >= RagDollItem.MAX_CRACKS) {
            doll.shrink(1);
            player.displayClientMessage(Component.translatable("message.anchors.doll_broke"), true);
            playAt(level, player.getX(), player.getY() + 1.0, player.getZ(), "block.wool.break", 1.0F, 0.5F);
        } else {
            RagDollItem.setCracks(doll, cracks);
            player.displayClientMessage(Component.translatable("message.anchors.doll_saved"), true);
            playAt(level, player.getX(), player.getY() + 1.0, player.getZ(), "block.wool.break", 1.0F, 0.7F);
        }
        playAt(level, player.getX(), player.getY() + 1.0, player.getZ(), "entity.warden.heartbeat", 1.0F, 0.8F);
    }

    /** Сила фонаря: видишь в темноте и видишь всех мобов рядом, даже через стены. */
    private static void useLanternPower(ServerPlayer player, ServerLevel level) {
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 300, 0, false, false, false));

        AABB area = player.getBoundingBox().inflate(16.0);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area)) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, false));
        }
    }

    /** Одна нота мелодии шкатулки. На стадии 2+ иногда откуда-то издалека отвечает искажённое эхо. */
    private static void playMusicBoxNote(ServerPlayer player, ServerLevel level) {
        int note = MELODY[(player.tickCount / NOTE_INTERVAL) % MELODY.length];
        if (note < 0) return;

        float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
        playAt(level, player.getX(), player.getY() + 1.0, player.getZ(), "block.note_block.bell", 0.8F, pitch);

        int stage = FadingData.get(level.getServer()).getStage();
        if (stage >= 2 && level.random.nextInt(6) == 0) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            playAt(level,
                    player.getX() + Math.cos(angle) * 10,
                    player.getY() + 1.0,
                    player.getZ() + Math.sin(angle) * 10,
                    "block.note_block.bell", 0.5F, Math.max(0.5F, pitch * 0.5F));
        }
    }

    /** Монстры рядом забывают про игрока и еле двигаются. */
    private static void calmMonsters(ServerPlayer player, ServerLevel level) {
        AABB area = player.getBoundingBox().inflate(12.0);
        for (Monster monster : level.getEntitiesOfClass(Monster.class, area)) {
            monster.setTarget(null);
            monster.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false, false));
        }
    }

    /** Что происходит с миром вокруг игрока, пока Угасание растёт. */
    private static void applyAtmosphere(ServerPlayer player, ServerLevel level, int stage) {
        if (stage <= 0) return;
        RandomSource random = level.random;

        // Стадия 1: странные далёкие звуки пещер
        if (random.nextInt(12) == 0) {
            playAround(level, player, "ambient.cave", 0.8F, 0.7F + random.nextFloat() * 0.3F);
        }

        // Стадия 2: далёкие двери и тёмные "провалы" в зрении
        if (stage >= 2) {
            if (random.nextInt(25) == 0) {
                playAround(level, player, "block.wooden_door.open", 0.6F, 0.6F);
            }
            if (random.nextInt(45) == 0) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 120, 0, false, false, false));
            }
        }

        // Стадия 3: тёмный дым в отдалении (в стороне от игрока)
        if (stage >= 3 && random.nextInt(3) == 0) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 6 + random.nextDouble() * 4;
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    player.getX() + Math.cos(angle) * dist,
                    player.getY() + 0.5,
                    player.getZ() + Math.sin(angle) * dist,
                    6, 0.4, 0.8, 0.4, 0.01);
        }

        // Стадия 4: здесь позже появится Коллекционер
    }

    private static void playAround(ServerLevel level, ServerPlayer player, String soundId, float volume, float pitch) {
        RandomSource r = level.random;
        double x = player.getX() + (r.nextDouble() - 0.5) * 24;
        double y = player.getY() + (r.nextDouble() - 0.5) * 6;
        double z = player.getZ() + (r.nextDouble() - 0.5) * 24;
        playAt(level, x, y, z, soundId, volume, pitch);
    }

    static void playAt(ServerLevel level, double x, double y, double z, String soundId, float volume, float pitch) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        if (sound == null) return;
        level.playSound(null, x, y, z, sound, SoundSource.AMBIENT, volume, pitch);
    }

    /** Команды для проверки: /fading get, /fading set <число>, /fading add <число> */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fading")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("get").executes(ctx -> {
                    FadingData data = FadingData.get(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                            "command.anchors.fading.get", (int) data.getFading(), data.getStage()), false);
                    return 1;
                }))
                .then(Commands.literal("set")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0)).executes(ctx -> {
                            FadingData data = FadingData.get(ctx.getSource().getServer());
                            data.set(DoubleArgumentType.getDouble(ctx, "value"));
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "command.anchors.fading.set", (int) data.getFading(), data.getStage()), true);
                            return 1;
                        })))
                .then(Commands.literal("add")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg()).executes(ctx -> {
                            FadingData data = FadingData.get(ctx.getSource().getServer());
                            data.add(DoubleArgumentType.getDouble(ctx, "value"));
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "command.anchors.fading.set", (int) data.getFading(), data.getStage()), true);
                            return 1;
                        }))));
    }
}
