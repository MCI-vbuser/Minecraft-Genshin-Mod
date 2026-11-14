package com.vbuser.genshin.command;

import com.vbuser.genshin.client.renderer.CloudDensityVolume;
import com.vbuser.genshin.client.renderer.CloudVolumeRenderer;
import com.vbuser.genshin.client.renderer.CloudVolumeTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Locale;

/**
 * Client side tuning command for the volumetric clouds:
 * {@code /cloud [stats|quality|extinction|detail|iso|sun|rescan|reload]}.
 */
@SideOnly(Side.CLIENT)
public class CommandCloud extends CommandBase {

    @Override
    public String getName() {
        return "cloud";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/cloud <stats|quality|extinction|detail|iso|sun|rescan|reload|vanilla>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        Minecraft mc = Minecraft.getMinecraft();
        CloudVolumeRenderer renderer = CloudVolumeRenderer.getInstance();
        String action = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "stats": {
                if (renderer == null) {
                    reply(sender, "cloud renderer not initialised");
                    return;
                }
                reply(sender, "cloud: " + renderer.describe());
                reply(sender, String.format(Locale.ROOT,
                        "blocks=%d sections=%d avgDensity=%.2f dirty=%d",
                        CloudVolumeTracker.getCloudBlockCount(mc.world == null ? 0 : mc.world.provider.getDimension()),
                        CloudVolumeTracker.getSectionCount(mc.world == null ? 0 : mc.world.provider.getDimension()),
                        CloudVolumeTracker.getAverageDensity(mc.world == null ? 0 : mc.world.provider.getDimension()),
                        CloudVolumeTracker.getDirtyCount(mc.world == null ? 0 : mc.world.provider.getDimension())));
                BlockPos min = mc.world == null ? null : CloudVolumeTracker.getMin(mc.world.provider.getDimension());
                BlockPos max = mc.world == null ? null : CloudVolumeTracker.getMax(mc.world.provider.getDimension());
                reply(sender, min == null ? "bounds: none"
                        : "bounds: " + min + " .. " + max);
                CloudDensityVolume volume = renderer.getVolume();
                reply(sender, String.format(Locale.ROOT,
                        "volume: cell=%d origin=(%d,%d,%d) dims=%dx%dx%d",
                        volume.getCellSize(), volume.getOriginX(), volume.getOriginY(), volume.getOriginZ(),
                        volume.getDimX(), volume.getDimY(), volume.getDimZ()));
                reply(sender, String.format(Locale.ROOT,
                        "extinction=%.3f detail=%.3f iso=%.3f..%.3f sun=%.2f scale=%.2f",
                        CloudVolumeRenderer.getExtinction(), CloudVolumeRenderer.getDetailAmount(),
                        CloudVolumeRenderer.getIsoLow(), CloudVolumeRenderer.getIsoHigh(),
                        CloudVolumeRenderer.getSunSoftness(), renderer.getRenderScale()));
                return;
            }
            case "quality": {
                if (args.length > 1) {
                    CloudVolumeRenderer.setQualityMode(parseQuality(args[1]));
                }
                reply(sender, "quality: " + CloudVolumeRenderer.qualityName(CloudVolumeRenderer.getQualityMode()));
                return;
            }
            case "extinction": {
                if (args.length > 1) {
                    CloudVolumeRenderer.setExtinction((float) parseDouble(args[1]));
                }
                reply(sender, "extinction: " + CloudVolumeRenderer.getExtinction());
                return;
            }
            case "detail": {
                if (args.length > 1) {
                    CloudVolumeRenderer.setDetailAmount((float) parseDouble(args[1]));
                }
                reply(sender, "detail: " + CloudVolumeRenderer.getDetailAmount());
                return;
            }
            case "sun": {
                if (args.length > 1) {
                    CloudVolumeRenderer.setSunSoftness((float) parseDouble(args[1]));
                }
                reply(sender, "sun shadow softness: " + CloudVolumeRenderer.getSunSoftness());
                return;
            }
            case "iso": {
                if (args.length > 2) {
                    CloudVolumeRenderer.setIso((float) parseDouble(args[1]), (float) parseDouble(args[2]));
                }
                reply(sender, "iso: " + CloudVolumeRenderer.getIsoLow() + " .. " + CloudVolumeRenderer.getIsoHigh());
                return;
            }
            case "rescan": {
                CloudVolumeRenderer.requestRescan(mc.world);
                reply(sender, "cloud rescan queued");
                return;
            }
            case "vanilla": {
                // The flat vanilla cloud layer is drawn at y=128 and cheaply
                // undercuts a volumetric sky; opt in explicitly.
                boolean off = args.length > 1 && "off".equalsIgnoreCase(args[1]);
                mc.gameSettings.clouds = off ? 0 : 2;
                mc.gameSettings.saveOptions();
                reply(sender, "vanilla clouds: " + (off ? "off" : "fancy"));
                return;
            }
            case "reload": {
                if (renderer == null) {
                    reply(sender, "cloud renderer not initialised");
                    return;
                }
                reply(sender, renderer.reloadShaders() ? "cloud shaders reloaded" : "cloud shader reload FAILED (see log)");
                return;
            }
            default:
                throw new CommandException("usage: " + this.getUsage(sender));
        }
    }

    private static int parseQuality(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "low":
                return CloudVolumeRenderer.QUALITY_LOW;
            case "medium":
            case "med":
                return CloudVolumeRenderer.QUALITY_MEDIUM;
            case "high":
                return CloudVolumeRenderer.QUALITY_HIGH;
            default:
                return CloudVolumeRenderer.QUALITY_AUTO;
        }
    }

    private static void reply(ICommandSender sender, String message) {
        sender.sendMessage(new TextComponentString(message));
    }
}
