package com.anchors;

import net.minecraft.world.item.BlockItem;
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

    public static final RegistryObject<Item> INVERTED_CROSS = ITEMS.register("inverted_cross",
            () -> new InvertedCrossItem(new Item.Properties().stacksTo(1)));

    // Предметы "???" (блоки глюков). В выживании сами не появляются, только при глюках, но их можно выдать командой.
    public static final RegistryObject<Item> GLITCH_BLOCK_ITEM = ITEMS.register("glitch_block",
            () -> new BlockItem(ModBlocks.GLITCH_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<Item> GLITCH_MEAT_ITEM = ITEMS.register("glitch_meat",
            () -> new BlockItem(ModBlocks.GLITCH_MEAT.get(), new Item.Properties()));

    public static final RegistryObject<Item> GLITCH_FACE_ITEM = ITEMS.register("glitch_face",
            () -> new BlockItem(ModBlocks.GLITCH_FACE.get(), new Item.Properties()));
}
