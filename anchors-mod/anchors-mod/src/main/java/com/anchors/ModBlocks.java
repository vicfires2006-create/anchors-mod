package com.anchors;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AnchorsMod.MOD_ID);

    /**
     * "Сломанная текстура" для глюков. В мире такого блока нет: сервер показывает его игроку
     * как ложный блок (см. GlitchEffects). У блока нет предмета, получить его нельзя.
     */
    public static final RegistryObject<Block> GLITCH_BLOCK = BLOCKS.register("glitch_block",
            () -> new Block(BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable()));

    /** Мясной фарш (картинка textures/item/glitch_meat.png). Тоже показывается только при глюках. */
    public static final RegistryObject<Block> GLITCH_MEAT = BLOCKS.register("glitch_meat",
            () -> new Block(BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable()));

    /** Красное лицо (картинка textures/item/glitch_face.png). Тоже показывается только при глюках. */
    public static final RegistryObject<Block> GLITCH_FACE = BLOCKS.register("glitch_face",
            () -> new Block(BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable()));
}
