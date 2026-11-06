package com.anchors;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, AnchorsMod.MOD_ID);

    /** Тема Коллекционера: файл assets/anchors/sounds/collector_theme.ogg (описан в assets/anchors/sounds.json). */
    public static final RegistryObject<SoundEvent> COLLECTOR_THEME = SOUNDS.register("collector_theme",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(AnchorsMod.MOD_ID, "collector_theme")));
}
