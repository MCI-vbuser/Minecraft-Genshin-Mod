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

        float nx = (x + offsetX) * 0.04f;
        float ny = (y + offsetY) * 0.05f;
        float nz = (z + offsetZ) * 0.04f;

        float wx = fbm(nx * 2.0f, ny * 2.0f, nz * 2.0f, 2, 2.0f, 0.5f) * 12.0f;
        float wy = fbm(nx * 2.0f + 100, ny * 2.0f, nz * 2.0f, 2, 2.0f, 0.5f) * 12.0f;
        float wz = fbm(nx * 2.0f, ny * 2.0f, nz * 2.0f + 100, 2, 2.0f, 0.5f) * 12.0f;

        float n = fbm(nx + wx * 0.05f, ny + wy * 0.05f, nz + wz * 0.05f, 4, 2.0f, 0.5f);
        n = (n + 1.0f) * 0.5f;

        float yRatio = (float)(y - minY) / Math.max(1.0f, (maxY - minY));
        float heightFactor = 1.0f - (float)Math.pow(Math.abs(yRatio * 2.0f - 1.0f), 3.0f);

        float weighted = n * heightFactor;

        float threshold = 0.4f;
        if (weighted < threshold) {
            return 0.0f;
        }

        float mapped = (weighted - threshold) / (1.0f - threshold);
        return mapped * mapped * (3 - 2 * mapped);
    }
}