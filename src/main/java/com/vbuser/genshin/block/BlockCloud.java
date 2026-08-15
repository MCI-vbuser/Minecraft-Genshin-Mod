package com.vbuser.genshin.block;

import com.vbuser.genshin.client.renderer.CloudVolumeTracker;
import com.vbuser.genshin.math.PerlinNoise;
import com.vbuser.genshin.util.IHasModel;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.Random;

@SuppressWarnings("all")
public class BlockCloud extends BlockBase implements IHasModel {
    public static final PropertyInteger DENSITY = PropertyInteger.create("density", 0, 15);

    public BlockCloud(String name, Material material) {
        super(name, material);
        this.setDefaultState(this.blockState.getBaseState().withProperty(DENSITY, 8));
        this.setSoundType(SoundType.SNOW);
        this.setHardness(0.1F);
        this.setLightOpacity(0);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, DENSITY);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState().withProperty(DENSITY, meta);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(DENSITY);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public BlockRenderLayer getBlockLayer() {
        return BlockRenderLayer.TRANSLUCENT;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    @Nullable
    public AxisAlignedBB getCollisionBoundingBox(IBlockState blockState, IBlockAccess worldIn, BlockPos pos) {
        return NULL_AABB;
    }

    @Override
    public int getLightOpacity(IBlockState state, IBlockAccess world, BlockPos pos) {
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean shouldSideBeRendered(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
        return false;
    }

    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        if (worldIn.isRemote) {
            CloudVolumeTracker.addCloud(worldIn.provider.getDimension(), pos, state.getValue(DENSITY));
        }
        super.onBlockAdded(worldIn, pos, state);
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (worldIn.isRemote) {
            CloudVolumeTracker.removeCloud(worldIn.provider.getDimension(), pos);
        }
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return NULL_AABB;
    }

    @Override
    public boolean canCollideCheck(IBlockState state, boolean hitIfLiquid) {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    private static float fbm(float x, float y, float z, int octaves, float lacunarity, float gain) {
        float value = 0.0f;
        float amplitude = 1.0f;
        float frequency = 1.0f;
        float maxVal = 0.0f;
        for (int i = 0; i < octaves; i++) {
            value += amplitude * PerlinNoise.noise3d(x * frequency, y * frequency, z * frequency);
            maxVal += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return value / maxVal;
    }

    public static float getCloudDensityNoise(int x, int y, int z, long seed, int minY, int maxY) {
        Random rand = new Random(seed);
        float offsetX = rand.nextFloat() * 10000;
        float offsetY = rand.nextFloat() * 10000;
        float offsetZ = rand.nextFloat() * 10000;

        float mainNoise = fbm(
                (x + offsetX) * 0.06f,
                (y + offsetY) * 0.02f,
                (z + offsetZ) * 0.06f,
                4,
                2.0f,
                0.5f
        );

        float detail = PerlinNoise.noise3d(
                (x + offsetX) * 0.3f,
                (y + offsetY) * 0.1f,
                (z + offsetZ) * 0.3f
        ) * 0.3f;

        float noise = mainNoise + detail;
        noise = (noise + 1.0f) * 0.5f;
        noise = Math.max(0, Math.min(1, noise));

        float mid = (minY + maxY) * 0.5f;
        float halfRange = (maxY - minY) * 0.5f;
        float heightFactor = (float) Math.exp(-Math.pow((y - mid) / (halfRange * 0.6f), 2));

        float weighted = noise * heightFactor;

        float threshold = 0.25f;
        if (weighted < threshold) {
            return 0.0f;
        }
        float mapped = (weighted - threshold) / (1.0f - threshold);
        mapped = mapped * mapped * (3 - 2 * mapped);
        mapped = (float) Math.pow(mapped, 0.8f);
        return mapped;
    }

}