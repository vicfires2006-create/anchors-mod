package com.anchors;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AnchorsMod.MOD_ID);

    /** Коллекционер. Высокий (2.3 блока), не горит, сам не появляется: его вызывают команда или сюжет. */
    public static final RegistryObject<EntityType<CollectorEntity>> COLLECTOR = ENTITIES.register("collector",
            () -> EntityType.Builder.of(CollectorEntity::new, MobCategory.MISC)
                    .sized(0.7F, 2.3F)
                    .fireImmune()
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build("collector"));

    @Mod.EventBusSubscriber(modid = AnchorsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class Events {
        @SubscribeEvent
        public static void onAttributes(EntityAttributeCreationEvent event) {
            event.put(COLLECTOR.get(), CollectorEntity.createAttributes().build());
        }
    }
}
