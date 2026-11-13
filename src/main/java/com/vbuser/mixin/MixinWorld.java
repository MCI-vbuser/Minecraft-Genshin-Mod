package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.chunk.ColumnManager;
import com.vbuser.inf.chunk.ExtendedColumn;
import com.vbuser.inf.light.ExtendedLightEngine;
import com.vbuser.inf.light.ExtendedWorldLight;
import com.vbuser.inf.network.ExtendedPacketQueue;
import com.vbuser.inf.network.SPacketExtendedTileEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEventData;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(World.class)
public abstract class MixinWorld implements ExtendedWorldLight {

    @Unique
    private final List<BlockEventData> inf$extendedBlockEvents = new ArrayList<>();

    @Unique
    private volatile ExtendedLightEngine inf$lightEngine;

    /**
     * Hot path (every block change reaches this through {@code ColumnLight}),
     * so the common case only reads a volatile field; only the first call takes
     * the lock.
     */
    @Unique
    @Override
    public ExtendedLightEngine inf$lightEngine() {
        ExtendedLightEngine engine = this.inf$lightEngine;
        return engine != null ? engine : inf$createLightEngine();
    }

    @Unique
    private synchronized ExtendedLightEngine inf$createLightEngine() {
        if (this.inf$lightEngine == null) {
            this.inf$lightEngine = new ExtendedLightEngine((World) (Object) this);
        }
        return this.inf$lightEngine;
    }

    @Inject(
            method = "addBlockEvent(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$addBlockEvent(BlockPos pos, Block blockIn, int eventID, int eventParam,
                                   CallbackInfo ci) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        if (!HeightConstants.isExtendedWorld((World) (Object) this)) {
            return;
        }

        BlockEventData data = new BlockEventData(pos, blockIn, eventID, eventParam);
        for (BlockEventData existing : inf$extendedBlockEvents) {
            if (existing.equals(data)) {
                ci.cancel();
                return;
            }
        }
        inf$extendedBlockEvents.add(data);
        ci.cancel();
    }

    @Inject(
            method = "updateEntities",
            at = @At("TAIL")
    )
    private void inf$updateExtendedBlockEvents(CallbackInfo ci) {
        if (inf$extendedBlockEvents.isEmpty()) {
            return;
        }

        World self = (World) (Object) this;
        List<BlockEventData> events = new ArrayList<>(inf$extendedBlockEvents);
        inf$extendedBlockEvents.clear();

        for (BlockEventData event : events) {
            if (!self.isBlockLoaded(event.getPosition())) {
                continue;
            }
            IBlockState state = self.getBlockState(event.getPosition());
            if (state.getBlock() == event.getBlock()) {
                state.getBlock().eventReceived(
                        state, self, event.getPosition(),
                        event.getEventID(), event.getEventParameter());
            }
        }
    }

    /**
     * Drain the extended light queues with a per tick budget. Light has to be
     * fully propagated before the frame is drawn, so this runs at the very end
     * of the world tick, on both sides.
     */
    @Inject(
            method = "updateEntities",
            at = @At("TAIL")
    )
    private void inf$tickLightEngine(CallbackInfo ci) {
        if (this.inf$lightEngine != null
                && HeightConstants.isExtendedWorld((World) (Object) this)) {
            this.inf$lightEngine.tick();
        }
    }

    /**
     * Route World.setBlockState's chunk write through the extended column while
     * preserving the caller's flags. World.setBlockState itself then performs
     * light checks and {@code markAndNotifyBlock} exactly as for vanilla blocks.
     */
    @Redirect(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/Chunk;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;"
            )
    )
    private IBlockState inf$setExtendedBlockState(Chunk chunk, BlockPos chunkPos, IBlockState chunkState,
                                                  BlockPos pos, IBlockState newState, int flags) {
        if (!HeightConstants.isExtendedY(chunkPos.getY())) {
            return chunk.setBlockState(chunkPos, chunkState);
        }
        if (!HeightConstants.isExtendedWorld((World) (Object) this)) {
            return null;
        }

        ExtendedColumn column = ColumnManager.get(chunk);
        IBlockState oldState = column.setBlock(
                (World) (Object) this,
                chunkPos.getX(), chunkPos.getY(), chunkPos.getZ(),
                chunkState, flags);

        if (oldState != null) {
            chunk.markDirty();
        }
        return oldState;
    }

    @Inject(
            method = "setTileEntity(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/tileentity/TileEntity;)V",
            at = @At("TAIL")
    )
    private void inf$setTileEntity(BlockPos pos, TileEntity te, CallbackInfo ci) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        if (te == null || te.isInvalid()) {
            return;
        }

        World self = (World) (Object) this;
        if (!HeightConstants.isExtendedWorld(self)) {
            return;
        }

        if (te.getWorld() != self) {
            te.setWorld(self);
        }
        if (!pos.equals(te.getPos())) {
            te.setPos(pos);
        }

        if (!(self instanceof WorldServer)) {
            return;
        }
        WorldServer ws = (WorldServer) self;

        Chunk chunk = ws.getChunkFromBlockCoords(pos);
        if (chunk == null || !chunk.isPopulated()) {
            // The chunk send path will include the tile entity.
            return;
        }

        NBTTagCompound nbt = te.getUpdateTag();
        if (nbt == null) {
            return;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        assert ws.getMinecraftServer() != null;
        int viewDist = ws.getMinecraftServer().getPlayerList().getViewDistance() + 1;

        SPacketExtendedTileEntity packet = new SPacketExtendedTileEntity(pos, nbt);
        for (EntityPlayer player : ws.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            EntityPlayerMP mp = (EntityPlayerMP) player;
            int dx = Math.abs(mp.chunkCoordX - chunkX);
            int dz = Math.abs(mp.chunkCoordZ - chunkZ);
            if (dx <= viewDist && dz <= viewDist) {
                ExtendedPacketQueue.sendTileEntity(mp, packet);
            }
        }
    }

    @Inject(
            method = "isBlockLoaded(Lnet/minecraft/util/math/BlockPos;Z)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$isBlockLoaded(BlockPos pos, boolean allowEmpty,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (HeightConstants.isExtendedY(pos.getY())
                && HeightConstants.isExtendedWorld((World) (Object) this)) {
            cir.setReturnValue(this.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4, allowEmpty));
        }
    }

    @Shadow
    protected abstract boolean isChunkLoaded(int x, int z, boolean allowEmpty);

    @Inject(
            method = "isOutsideBuildHeight(Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$isOutsideBuildHeight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        // Extended Y only exists in the extended dimension; everywhere else it is
        // outside the build height, so World.setBlockState rejects it before the
        // (unbounded) vanilla chunk arrays are touched.
        cir.setReturnValue(!HeightConstants.isExtendedWorld((World) (Object) this));
    }

    /**
     * Extended light is owned by {@link ExtendedLightEngine}; vanilla's bounded
     * BFS must not run for those cells or the two would fight over them.
     */
    @Inject(
            method = "checkLight(Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$checkLight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (HeightConstants.isExtendedY(pos.getY())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * {@code getLightFor} clamps Y into {@code 0..255}; extended Y is served
     * straight from the chunk, which routes it to the column light.
     */
    @Inject(
            method = "getLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLightFor(EnumSkyBlock type, BlockPos pos,
                                 CallbackInfoReturnable<Integer> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        World self = (World) (Object) this;
        if (!self.isValid(pos) || !self.isBlockLoaded(pos)) {
            cir.setReturnValue(type.defaultLightValue);
            return;
        }
        cir.setReturnValue(self.getChunkFromBlockCoords(pos).getLightFor(type, pos));
    }

    /** Same clamp removal for {@code World.getLight}, used by entities. */
    @Inject(
            method = "getLight(Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLight(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        World self = (World) (Object) this;
        cir.setReturnValue(self.getChunkFromBlockCoords(pos).getLightSubtracted(pos, 0));
    }

    @Inject(
            method = "getLight(Lnet/minecraft/util/math/BlockPos;Z)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$getLight(BlockPos pos, boolean checkNeighbors,
                              CallbackInfoReturnable<Integer> cir) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }
        cir.setReturnValue(inf$extendedLight(pos, checkNeighbors));
    }

    @Unique
    private int inf$extendedLight(BlockPos pos, boolean checkNeighbors) {
        World self = (World) (Object) this;
        int x = pos.getX();
        int z = pos.getZ();
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) {
            return 15;
        }
        if (checkNeighbors && self.getBlockState(pos).useNeighborBrightness()) {
            int max = self.getLight(pos.up(), false);
            int value = self.getLight(pos.east(), false);
            if (value > max) {
                max = value;
            }
            value = self.getLight(pos.west(), false);
            if (value > max) {
                max = value;
            }
            value = self.getLight(pos.south(), false);
            if (value > max) {
                max = value;
            }
            value = self.getLight(pos.north(), false);
            if (value > max) {
                max = value;
            }
            return max;
        }
        return self.getChunkFromBlockCoords(pos).getLightSubtracted(pos, self.getSkylightSubtracted());
    }

    /**
     * Extended coordinates have no vanilla render section to notify; the seam
     * layers are mirrored onto their vanilla neighbor by the engine instead.
     */
    @Inject(
            method = "notifyLightSet(Lnet/minecraft/util/math/BlockPos;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$notifyLightSet(BlockPos pos, CallbackInfo ci) {
        if (HeightConstants.isExtendedY(pos.getY())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "notifyBlockUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/block/state/IBlockState;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$notifyBlockUpdate(BlockPos pos, IBlockState oldState, IBlockState newState, int flags,
                                       CallbackInfo ci) {
        if (!HeightConstants.isExtendedY(pos.getY())) {
            return;
        }

        World self = (World) (Object) this;
        if (!HeightConstants.isExtendedWorld(self)) {
            ci.cancel();
            return;
        }
        if (!(self instanceof WorldServer)) {
            ci.cancel();
            return;
        }

        Chunk chunk = self.getChunkFromBlockCoords(pos);
        if (chunk == null || !chunk.isPopulated()) {
            ci.cancel();
            return;
        }

        WorldServer ws = (WorldServer) self;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        assert ws.getMinecraftServer() != null;
        int viewDist = ws.getMinecraftServer().getPlayerList().getViewDistance() + 1;

        SPacketBlockChange blockPacket = new SPacketBlockChange(self, pos);
        TileEntity tileEntity = newState.getBlock().hasTileEntity(newState) ? self.getTileEntity(pos) : null;
        NBTTagCompound updateTag = tileEntity != null ? tileEntity.getUpdateTag() : null;
        SPacketExtendedTileEntity tilePacket = updateTag != null
                ? new SPacketExtendedTileEntity(pos, updateTag)
                : null;

        for (EntityPlayer player : ws.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            EntityPlayerMP mp = (EntityPlayerMP) player;
            int dx = Math.abs(mp.chunkCoordX - chunkX);
            int dz = Math.abs(mp.chunkCoordZ - chunkZ);
            if (dx <= viewDist && dz <= viewDist) {
                ExtendedPacketQueue.sendBlockChange(mp, blockPacket);
                if (tilePacket != null) {
                    ExtendedPacketQueue.sendTileEntity(mp, tilePacket);
                }
            }
        }

        ci.cancel();
    }

    @Inject(
            method = "isAreaLoaded(IIIIIIZ)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$isAreaLoaded(int x1, int y1, int z1, int x2, int y2, int z2, boolean allowEmpty,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (y2 < HeightConstants.VANILLA_MIN_Y || y1 > HeightConstants.VANILLA_MAX_Y) {
            if (!HeightConstants.isExtendedWorld((World) (Object) this)) {
                cir.setReturnValue(false);
                return;
            }
            int cx1 = x1 >> 4;
            int cz1 = z1 >> 4;
            int cx2 = x2 >> 4;
            int cz2 = z2 >> 4;
            for (int i = cx1; i <= cx2; i++) {
                for (int j = cz1; j <= cz2; j++) {
                    if (!this.isChunkLoaded(i, j, allowEmpty)) {
                        cir.setReturnValue(false);
                        return;
                    }
                }
            }
            cir.setReturnValue(true);
        }
    }
}
