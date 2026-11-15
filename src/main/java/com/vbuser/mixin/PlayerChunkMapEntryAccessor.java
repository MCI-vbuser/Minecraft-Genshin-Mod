package com.vbuser.mixin;

import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the world updater swap the chunk a {@link PlayerChunkMapEntry} is
 * pointing at after the region file underneath it was rewritten.
 *
 * <p>Without this the entry keeps the pre-merge {@link Chunk} object and any
 * later multi-block / whole-section packet it builds for the player would be
 * made from stale data.</p>
 */
@Mixin(PlayerChunkMapEntry.class)
public interface PlayerChunkMapEntryAccessor {

    @Accessor("chunk")
    void inf$setChunk(Chunk chunk);
}
