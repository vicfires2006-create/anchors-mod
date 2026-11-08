package com.anchors;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AnchorsMod.MOD_ID);

    /**
     * Три "сломанных" блока. Игрок видит их как "???". Их можно получить командой
     * (/give @s anchors:glitch_block, anchors:glitch_meat, anchors:glitch_face)
     * или поставить командой /setblock. Ничего не выпадает при поломке.
     */
    public static final RegistryObject<Block> GLITCH_BLOCK = BLOCKS.register("glitch_block", ModBlocks::glitchBlock);

    /** Мясной фарш (картинка textures/item/glitch_meat.png). */
    public static final RegistryObject<Block> GLITCH_MEAT = BLOCKS.register("glitch_meat", ModBlocks::glitchBlock);

    /** Красное лицо (картинка textures/item/glitch_face.png). */
    public static final RegistryObject<Block> GLITCH_FACE = BLOCKS.register("glitch_face", ModBlocks::glitchBlock);

    private static Block glitchBlock() {
        return new Block(BlockBehaviour.Properties.of().strength(1.0F, 3600000.0F).noLootTable());
    }

    public static boolean isGlitchBlock(BlockState state) {
        return state.is(GLITCH_BLOCK.get()) || state.is(GLITCH_MEAT.get()) || state.is(GLITCH_FACE.get());
    }
}
