package com.anchors;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AnchorsMod.MOD_ID);

    public static final RegistryObject<Item> OLD_LANTERN = ITEMS.register("old_lantern",
            () -> new OldLanternItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> MUSIC_BOX = ITEMS.register("music_box",
            () -> new MusicBoxItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> RAG_DOLL = ITEMS.register("rag_doll",
            () -> new RagDollItem(new Item.Properties().stacksTo(1)));
}
