package com.vbuser.mixin;

import com.vbuser.inf.HeightConstants;
import com.vbuser.inf.InfiniteHeight;
import com.vbuser.inf.network.SPacketExtendedTileEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(World.class)
public abstract class MixinWorld {

    @Shadow
    protected abstract boolean isChunkLoaded(int x, int z, boolean allowEmpty);

    @Inject(
            method = "isOutsideBuildHeight(Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$isOutsideBuildHeight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(!HeightConstants.isValidY(pos.getY()));
    }

    @Inject(
            method = "checkLightFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void inf$checkLightFor(EnumSkyBlock lightType, BlockPos pos,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (HeightConstants.isExtendedY(pos.getY())) {
            cir.setReturnValue(false);
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
        if (!(self instanceof WorldServer)) {
            ci.cancel();
            return;
        }

        WorldServer ws = (WorldServer) self;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        int viewDist = ws.getMinecraftServer().getPlayerList().getViewDistance() + 1;

        List<EntityPlayerMP> watchers = new ArrayList<>();
        for (EntityPlayer player : ws.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            EntityPlayerMP mp = (EntityPlayerMP) player;
            int dx = Math.abs(mp.chunkCoordX - chunkX);
            int dz = Math.abs(mp.chunkCoordZ - chunkZ);
            if (dx <= viewDist && dz <= viewDist) {
                watchers.add(mp);
            }
        }

        if (watchers.isEmpty()) {
            ci.cancel();
            return;
        }

        SPacketBlockChange blockPacket = new SPacketBlockChange(self, pos);
        for (EntityPlayerMP player : watchers) {
            player.connection.sendPacket(blockPacket);
        }

        if (newState.getBlock().hasTileEntity(newState)) {
            TileEntity te = self.getTileEntity(pos);
            if (te != null) {
                NBTTagCompound nbt = te.getUpdateTag();
                if (nbt != null) {
                    SPacketExtendedTileEntity tePacket =
                            new SPacketExtendedTileEntity(pos, nbt);
                    for (EntityPlayerMP player : watchers) {
                        InfiniteHeight.NETWORK.sendTo(tePacket, player);
                    }
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