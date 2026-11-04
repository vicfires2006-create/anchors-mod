package com.anchors;

import com.mojang.brigadier.arguments.DoubleArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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

        FadingData data = FadingData.get(level.getServer());

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

    private static void playAt(ServerLevel level, double x, double y, double z, String soundId, float volume, float pitch) {
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
