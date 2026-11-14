package com.vbuser.genshin.block;

import com.vbuser.genshin.client.renderer.CloudVolumeRenderer;
import com.vbuser.genshin.math.CloudShape;
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
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;

/**
 * A cloud block.
 *
 * <p>Cloud blocks are a data store, not geometry: the {@code DENSITY} property
 * is read by {@link com.vbuser.genshin.client.renderer.CloudVolumeTracker} and
 * turned into a 3D density texture that the volumetric renderer raymarches.
 * The block itself renders nothing, except when the volumetric renderer could
 * not initialise (no 3D textures / shader failure), in which case it falls back
 * to plain translucent cubes so clouds never disappear entirely.</p>
 */
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
        // The volumetric renderer draws every cloud block at once; drawing the
        // cubes as well would double the cloud. Only fall back to cubes when the
        // volume path is unavailable.
        return !CloudVolumeRenderer.isVolumeAvailable();
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

    /** Shape sampled by {@code /cloudfill}; kept for callers of the old API. */
    public static float getCloudDensityNoise(int x, int y, int z, long seed, int minY, int maxY) {
        return CloudShape.density(x, y, z, seed, minY, maxY);
    }
}
