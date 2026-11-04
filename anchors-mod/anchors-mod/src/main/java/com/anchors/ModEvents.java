package com.anchors;

import com.mojang.brigadier.arguments.DoubleArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = AnchorsMod.MOD_ID)
public class ModEvents {

    /** Сколько "Угасания" добавляется за каждую секунду с включённым фонарём. */
    private static final double FADING_PER_SECOND = 1.0;

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0) return; // раз в секунду

        ServerLevel level = player.serverLevel();
        FadingData data = FadingData.get(level.getServer());

        if (OldLanternItem.holdsActive(player)) {
            int before = data.getStage();
            data.add(FADING_PER_SECOND);
            int after = data.getStage();

            useLanternPower(player, level);

            if (after > before) {
                player.displayClientMessage(Component.translatable("message.anchors.stage_" + after), true);
            }
        }

        applyAtmosphere(player, level, data.getStage());
    }

    /** Сила фонаря: видишь в темноте и видишь всех мобов рядом, даже через стены. */
    private static void useLanternPower(ServerPlayer player, ServerLevel level) {
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 300, 0, false, false, false));

        AABB area = player.getBoundingBox().inflate(16.0);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area)) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, false));
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
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        if (sound == null) return;
        RandomSource r = level.random;
        double x = player.getX() + (r.nextDouble() - 0.5) * 24;
        double y = player.getY() + (r.nextDouble() - 0.5) * 6;
        double z = player.getZ() + (r.nextDouble() - 0.5) * 24;
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
