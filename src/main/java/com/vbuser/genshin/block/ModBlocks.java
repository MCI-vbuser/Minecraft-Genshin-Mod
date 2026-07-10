package com.vbuser.genshin.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class ModBlocks {
    public static final List<Block> BLOCKS = new ArrayList<>();

    public static final Block CLOUD_BLOCK = new BlockCloud("cloud_block", Material.SNOW);
}
