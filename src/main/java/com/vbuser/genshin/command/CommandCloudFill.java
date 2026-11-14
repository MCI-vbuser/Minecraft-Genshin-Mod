package com.vbuser.genshin.command;

import com.vbuser.genshin.block.BlockCloud;
import com.vbuser.genshin.block.ModBlocks;
import com.vbuser.genshin.math.CloudShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /cloudfill <x1> <y1> <z1> <x2> <y2> <z2> <baseDensity> [noiseSeed]}
 *
 * <p>The shape is baked on a worker thread (one {@code byte[4096]} per 16x16x16
 * section) and then applied to the world from a server tick handler with a time
 * budget. The previous implementation scheduled one server task per block,
 * which for a 300x40x300 region meant ~3.6 million tasks plus a full
 * {@code setBlockState} (and a block update packet) for every air block in the
 * box, and a {@code new Random(seed)} per density sample.</p>
 */
@SuppressWarnings("all")
public class CommandCloudFill extends CommandBase {

    /** Wall clock budget for world writes per server tick. */
    private static final long WRITE_BUDGET_NANOS = 8_000_000L;

    private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();

    @Override
    public String getName() {
        return "cloudfill";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "cloudfill <x1> <y1> <z1> <x2> <y2> <z2> <baseDensity> [noiseSeed]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 7) {
            sender.sendMessage(new TextComponentString(
                    "Usage: /cloudfill <x1> <y1> <z1> <x2> <y2> <z2> <baseDensity> [noiseSeed]"));
            return;
        }

        BlockPos from = parseBlockPos(sender, args, 0, false);
        BlockPos to = parseBlockPos(sender, args, 3, false);

        BlockPos min = new BlockPos(
                Math.min(from.getX(), to.getX()),
                Math.min(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()));
        BlockPos max = new BlockPos(
                Math.max(from.getX(), to.getX()),
                Math.max(from.getY(), to.getY()),
                Math.max(from.getZ(), to.getZ()));

        int baseDensity = parseInt(args[6], 0, 15);
        long noiseSeed = new Random().nextLong();
        if (args.length > 7) {
            noiseSeed = Long.parseLong(args[7]);
        }

        World world = sender.getEntityWorld();
        if (world == null) {
            return;
        }
        int dimension = world.provider.getDimension();
        final long finalSeed = noiseSeed;
        final int finalBase = baseDensity;
        final BlockPos finalMin = min;
        final BlockPos finalMax = max;

        long totalBlocks = (long) (max.getX() - min.getX() + 1)
                * (long) (max.getY() - min.getY() + 1)
                * (long) (max.getZ() - min.getZ() + 1);

        sender.sendMessage(new TextComponentString(String.format(
                "cloudfill: %d blocks, density %d, seed %d", totalBlocks, baseDensity, noiseSeed)));

        if (baseDensity == 0) {
            Job job = new Job(server, sender, world, dimension, finalMin, finalMax, null, true, 0L);
            JOBS.add(job);
            return;
        }

        CompletableFuture.supplyAsync(() -> bake(finalMin, finalMax, finalSeed, finalBase))
                .thenAccept(sections -> {
                    if (sections == null) {
                        return;
                    }
                    server.addScheduledTask(() -> {
                        Job job = new Job(server, sender, world, dimension, finalMin, finalMax,
                                sections, false, totalBlocks);
                        JOBS.add(job);
                    });
                });
    }

    // ------------------------------------------------------------------
    // shape baking (worker thread)
    // ------------------------------------------------------------------

    private static List<SectionFill> bake(BlockPos min, BlockPos max, long seed, int baseDensity) {
        List<SectionFill> out = new ArrayList<>();
        int minY = min.getY();
        int maxY = max.getY();
        int sx0 = min.getX() >> 4;
        int sx1 = max.getX() >> 4;
        int sy0 = min.getY() >> 4;
        int sy1 = max.getY() >> 4;
        int sz0 = min.getZ() >> 4;
        int sz1 = max.getZ() >> 4;
        for (int sy = sy0; sy <= sy1; sy++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sx = sx0; sx <= sx1; sx++) {
                    if (Thread.interrupted()) {
                        return null;
                    }
                    byte[] density = CloudShape.bakeSection(sx, sy, sz, seed, minY, maxY, baseDensity);
                    if (density != null) {
                        out.add(new SectionFill(sx, sy, sz, density));
                    }
                }
            }
        }
        return out;
    }

    private static final class SectionFill {
        final int sx;
        final int sy;
        final int sz;
        final byte[] density;

        SectionFill(int sx, int sy, int sz, byte[] density) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.density = density;
        }
    }

    // ------------------------------------------------------------------
    // world writing (server tick)
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) {
            return;
        }
        Job job = JOBS.peek();
        if (job == null) {
            return;
        }
        long deadline = System.nanoTime() + WRITE_BUDGET_NANOS;
        job.step(deadline);
        if (job.done) {
            JOBS.poll();
            job.finish();
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            return;
        }
        World world = event.getWorld();
        JOBS.removeIf(job -> job.world == world);
    }

    private static final class Job {
        final MinecraftServer server;
        final ICommandSender sender;
        final World world;
        final int dimension;
        final BlockPos min;
        final BlockPos max;
        final List<SectionFill> sections;
        final boolean clearing;
        final long totalBlocks;

        int sectionIndex;
        int blockIndex;
        long processed;
        int changed;
        int lastPercent;
        boolean done;
        final long startNanos = System.nanoTime();

        Job(MinecraftServer server, ICommandSender sender, World world, int dimension,
            BlockPos min, BlockPos max, List<SectionFill> sections, boolean clearing, long totalBlocks) {
            this.server = server;
            this.sender = sender;
            this.world = world;
            this.dimension = dimension;
            this.min = min;
            this.max = max;
            this.sections = sections;
            this.clearing = clearing;
            this.totalBlocks = totalBlocks;
        }

        void step(long deadline) {
            if (this.world == null || this.world.provider == null
                    || this.world.provider.getDimension() != this.dimension) {
                this.done = true;
                return;
            }
            if (this.clearing) {
                this.stepClear(deadline);
            } else {
                this.stepFill(deadline);
            }
        }

        private void stepClear(long deadline) {
            IBlockState air = Blocks.AIR.getDefaultState();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int sizeX = this.max.getX() - this.min.getX() + 1;
            int sizeZ = this.max.getZ() - this.min.getZ() + 1;
            long volume = (long) sizeX * sizeZ * (this.max.getY() - this.min.getY() + 1);
            while (this.processed < volume) {
                long index = this.processed++;
                int x = this.min.getX() + (int) (index % sizeX);
                int z = this.min.getZ() + (int) ((index / sizeX) % sizeZ);
                int y = this.min.getY() + (int) (index / ((long) sizeX * sizeZ));
                if ((index & 1023L) == 0L && System.nanoTime() > deadline) {
                    this.report();
                    return;
                }
                cursor.setPos(x, y, z);
                if (!(this.world.getBlockState(cursor).getBlock() instanceof BlockCloud)) {
                    continue;
                }
                this.world.setBlockState(cursor, air, 2);
                this.changed++;
            }
            this.done = true;
        }

        private void stepFill(long deadline) {
            if (this.sections == null) {
                this.done = true;
                return;
            }
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (this.sectionIndex < this.sections.size()) {
                SectionFill section = this.sections.get(this.sectionIndex);
                int baseX = section.sx << 4;
                int baseY = section.sy << 4;
                int baseZ = section.sz << 4;
                while (this.blockIndex < 4096) {
                    int index = this.blockIndex++;
                    if ((this.processed & 1023L) == 0L && System.nanoTime() > deadline) {
                        this.report();
                        return;
                    }
                    this.processed++;
                    int density = section.density[index] & 0xFF;
                    if (density == 0) {
                        continue;
                    }
                    int x = baseX + (index & 15);
                    int y = baseY + ((index >> 8) & 15);
                    int z = baseZ + ((index >> 4) & 15);
                    if (x < this.min.getX() || x > this.max.getX()
                            || y < this.min.getY() || y > this.max.getY()
                            || z < this.min.getZ() || z > this.max.getZ()) {
                        continue;
                    }
                    IBlockState current = this.world.getBlockState(cursor.setPos(x, y, z));
                    if (current.getBlock() instanceof BlockCloud
                            && current.getValue(BlockCloud.DENSITY) == density) {
                        continue;
                    }
                    this.world.setBlockState(cursor, ModBlocks.CLOUD_BLOCK.getDefaultState()
                            .withProperty(BlockCloud.DENSITY, density), 2);
                    this.changed++;
                    if (System.nanoTime() > deadline) {
                        this.report();
                        return;
                    }
                }
                this.sectionIndex++;
                this.blockIndex = 0;
            }
            this.done = true;
        }

        /** Reports progress based on baked blocks, throttled to whole percents. */
        private void report() {
            this.lastPercent = progressPercent(this.processed, this.totalBlocks, this.lastPercent);
        }

        void finish() {
            this.report();
            if (this.sender != null) {
                long millis = (System.nanoTime() - this.startNanos) / 1_000_000L;
                this.sender.sendMessage(new TextComponentString(String.format(
                        "cloudfill done: %d blocks in %d ms (%d changed)",
                        this.processed, millis, this.changed)));
            }
        }

        private int progressPercent(long processed, long total, int lastPercent) {
            if (this.sender == null || total <= 0L) {
                return lastPercent;
            }
            int percent = (int) Math.min(100L, processed * 100L / total);
            if (percent >= lastPercent + 5) {
                this.sender.sendMessage(new TextComponentString(String.format(
                        "cloudfill: %d%% (%d/%d)", percent, processed, total)));
                return percent;
            }
            return lastPercent;
        }
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }
}
