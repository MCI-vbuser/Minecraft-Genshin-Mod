package com.vbuser.genshin.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Volumetric cloud renderer.
 *
 * <p>Pipeline per frame:</p>
 * <ol>
 *   <li>the cloud block field is uploaded as a 3D density texture
 *       ({@link CloudDensityVolume}, incremental, frame budgeted),</li>
 *   <li>a full screen pass raymarches that volume at a reduced resolution,</li>
 *   <li>the result is upsampled with a depth aware filter.</li>
 * </ol>
 *
 * <p>Cheap things that are aggressively avoided: the pass is skipped entirely
 * when the camera cannot see the volume, the screen area outside the projected
 * volume is scissored away, the depth buffer is only copied when the pass
 * actually runs, and the resolution / step count follow the measured frame time
 * (with a fixed quality override available through {@code /cloud}).</p>
 */
@SideOnly(Side.CLIENT)
public class CloudVolumeRenderer {

    public static final int QUALITY_AUTO = 0;
    public static final int QUALITY_LOW = 1;
    public static final int QUALITY_MEDIUM = 2;
    public static final int QUALITY_HIGH = 3;

    private static final String SHADER_PATH = "assets/genshin/shaders/";
    private static final float MIN_SCALE = 0.3F;
    private static final float MAX_SCALE = 1.0F;
    /** Volume rebuild budget per frame, nanoseconds. */
    private static final long REBUILD_BUDGET = 2_000_000L;
    /** Chunk scan budget per client tick, nanoseconds. */
    private static final long SCAN_BUDGET = 2_500_000L;
    /** Skip the pass when the volume is further away than this. */
    private static final double MAX_VOLUME_DISTANCE = 1200.0D;

    // ---- user tunables (exposed through /cloud) ----
    private static int qualityMode = QUALITY_AUTO;
    private static float extinction = 0.25F;
    private static float detailAmount = 0.15F;
    private static float detailScale = 0.055F;
    private static float isoLow = 0.045F;
    private static float isoHigh = 0.17F;
    private static float sunSoftness = 1.0F;

    private static volatile boolean volumeAvailable;

    private static CloudVolumeRenderer instance;

    public static CloudVolumeRenderer getInstance() {
        return instance;
    }

    public static boolean isVolumeAvailable() {
        return volumeAvailable;
    }

    public static int getQualityMode() {
        return qualityMode;
    }

    public static void setQualityMode(int mode) {
        qualityMode = MathHelper.clamp(mode, QUALITY_AUTO, QUALITY_HIGH);
    }

    public static float getExtinction() {
        return extinction;
    }

    public static void setExtinction(float value) {
        extinction = MathHelper.clamp(value, 0.01F, 1.0F);
    }

    public static float getDetailAmount() {
        return detailAmount;
    }

    public static void setDetailAmount(float value) {
        detailAmount = MathHelper.clamp(value, 0.0F, 0.6F);
    }

    public static float getIsoLow() {
        return isoLow;
    }

    public static float getIsoHigh() {
        return isoHigh;
    }

    public static void setIso(float low, float high) {
        isoLow = MathHelper.clamp(low, 0.0F, 0.9F);
        isoHigh = MathHelper.clamp(high, isoLow + 0.005F, 1.0F);
    }

    public static float getSunSoftness() {
        return sunSoftness;
    }

    public static void setSunSoftness(float value) {
        sunSoftness = MathHelper.clamp(value, 0.1F, 4.0F);
    }

    // ---- GL objects ----
    private CloudShaderProgram volumeProgram;
    private CloudShaderProgram compositeProgram;

    private int depthTexture = -1;
    private int depthWidth = -1;
    private int depthHeight = -1;

    private int cloudFbo = -1;
    private int cloudColorTex = -1;
    private int cloudWidth = -1;
    private int cloudHeight = -1;

    private final CloudDensityVolume volume = new CloudDensityVolume();
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    private boolean initialized;
    private boolean failed;
    private int lastDimension = Integer.MIN_VALUE;

    /** Resolution multiplier of the raymarch buffer. */
    private float renderScale = 0.45F;
    /** Smoothed frame time in milliseconds. */
    private float frameMsEma = 16.7F;
    private long lastFrameNanos;
    private int framesSinceQualityChange;
    private int targetW;
    private int targetH;
    private float lastNearPlane = 0.05F;
    private float lastFarPlane = 256.0F;

    // GPU timer (ARB_timer_query). Measuring the pass itself beats guessing from
    // the frame time, and it lets auto quality react to the cloud cost instead
    // of to whatever else the scene is doing.
    private boolean timerSupported;
    private int timerQuery = -1;
    private boolean timerActive;
    private boolean queryInFlight;
    private float gpuMsEma;
    private int gpuSamples;

    public CloudVolumeRenderer() {
        instance = this;
        try {
            int max3D = GL11.glGetInteger(GL12.GL_MAX_3D_TEXTURE_SIZE);
            String version = GL11.glGetString(GL11.GL_VERSION);
            boolean redFormats = majorVersion(version) >= 3;
            this.volume.init(max3D, redFormats);
            this.initGpuTimer();
            this.reloadShaders();
            this.initialized = true;
            this.syncAvailability();
        } catch (Throwable t) {
            System.err.println("[CloudVolumeRenderer] Initialisation failed, falling back to block rendering.");
            t.printStackTrace();
            this.failed = true;
            volumeAvailable = false;
        }
    }

    private static int majorVersion(String version) {
        if (version == null || version.isEmpty()) {
            return 2;
        }
        int dot = version.indexOf('.');
        try {
            return Integer.parseInt(dot > 0 ? version.substring(0, dot) : version);
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }

    public boolean reloadShaders() {
        try {
            CloudShaderProgram volumeProgram = CloudShaderProgram.create("cloud_volume",
                    SHADER_PATH + "cloud_volume.vert", SHADER_PATH + "cloud_volume.frag");
            CloudShaderProgram compositeProgram = CloudShaderProgram.create("cloud_composite",
                    SHADER_PATH + "cloud_composite.vert", SHADER_PATH + "cloud_composite.frag");
            if (this.volumeProgram != null) {
                this.volumeProgram.dispose();
            }
            if (this.compositeProgram != null) {
                this.compositeProgram.dispose();
            }
            this.volumeProgram = volumeProgram;
            this.compositeProgram = compositeProgram;
            this.failed = false;
            this.syncAvailability();
            return true;
        } catch (IOException e) {
            System.err.println("[CloudVolumeRenderer] Shader reload failed: " + e.getMessage());
            this.failed = true;
            this.syncAvailability();
            return false;
        }
    }

    /**
     * Keeps the block fallback in step with the volume path. When the flag flips
     * the chunk meshes have to be rebuilt, because {@code shouldSideBeRendered}
     * decided whether to emit cloud quads when they were baked.
     */
    private void syncAvailability() {
        boolean available = this.initialized && !this.failed && this.volume.isSupported();
        if (available == volumeAvailable) {
            return;
        }
        volumeAvailable = available;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    public boolean isFailed() {
        return this.failed;
    }

    public CloudDensityVolume getVolume() {
        return this.volume;
    }

    public float getRenderScale() {
        return this.renderScale;
    }

    public float getFrameMsEma() {
        return this.frameMsEma;
    }

    /** GPU milliseconds spent in the cloud passes, as measured by a GL timer. */
    public float getGpuMsEma() {
        return this.gpuMsEma;
    }

    public boolean hasGpuTimer() {
        return this.timerSupported && this.gpuSamples > 0;
    }

    private void initGpuTimer() {
        try {
            this.timerSupported = org.lwjgl.opengl.GLContext.getCapabilities().GL_ARB_timer_query;
            if (this.timerSupported) {
                this.timerQuery = GL15.glGenQueries();
            }
        } catch (Throwable t) {
            this.timerSupported = false;
            this.timerQuery = -1;
        }
    }

    private void beginCloudTimer() {
        if (!this.timerSupported || this.timerQuery == -1) {
            return;
        }
        if (this.queryInFlight) {
            long available = ARBTimerQuery.glGetQueryObjecti64(this.timerQuery, QUERY_RESULT_AVAILABLE);
            if (available == 0L) {
                return;
            }
            long nanos = ARBTimerQuery.glGetQueryObjecti64(this.timerQuery, QUERY_RESULT);
            this.queryInFlight = false;
            if (nanos > 0L) {
                float millis = nanos / 1_000_000.0F;
                if (this.gpuSamples == 0) {
                    this.gpuMsEma = millis;
                } else {
                    this.gpuMsEma += (millis - this.gpuMsEma) * 0.1F;
                }
                this.gpuSamples++;
            }
        }
        GL15.glBeginQuery(ARBTimerQuery.GL_TIME_ELAPSED, this.timerQuery);
        this.timerActive = true;
    }

    private void endCloudTimer() {
        if (!this.timerActive) {
            return;
        }
        this.timerActive = false;
        GL15.glEndQuery(ARBTimerQuery.GL_TIME_ELAPSED);
        this.queryInFlight = true;
    }

    private static final int QUERY_RESULT = 0x8866;
    private static final int QUERY_RESULT_AVAILABLE = 0x8867;

    public String describe() {
        CloudDensityVolume volume = this.volume;
        return String.format(Locale.ROOT,
                "scale=%.2f steps=%d target=%dx%d frame=%.1fms gpu=%.2fms vol=%dx%dx%d cell=%d mode=%s",
                this.renderScale, stepCount(), this.targetW, this.targetH,
                this.frameMsEma, this.hasGpuTimer() ? this.gpuMsEma : -1.0F,
                volume.getDimX(), volume.getDimY(), volume.getDimZ(), volume.getCellSize(),
                qualityName(qualityMode));
    }

    public static String qualityName(int mode) {
        switch (mode) {
            case QUALITY_LOW:
                return "low";
            case QUALITY_MEDIUM:
                return "medium";
            case QUALITY_HIGH:
                return "high";
            default:
                return "auto";
        }
    }

    private int stepCount() {
        // Step count is cheap in the sparse case (a texel fetch replaced the fBm
        // evaluation and transmittance terminates the ray early) but it is the
        // dominant cost when the screen is filled with cloud, so the presets stay
        // moderate; the stochastic march and the depth aware upsample hide the
        // quantisation.
        if (this.renderScale >= 0.9F) {
            return 56;
        }
        if (this.renderScale >= 0.38F) {
            return 44;
        }
        if (this.renderScale >= 0.32F) {
            return 36;
        }
        return 28;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        CloudVolumeTracker.processPendingScans(mc.world, SCAN_BUDGET);
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        World world = event.getWorld();
        if (world == null || !world.isRemote) {
            return;
        }
        CloudVolumeTracker.removeChunk(world.provider.getDimension(), event.getChunk().getPos());
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world == null || !world.isRemote) {
            return;
        }
        CloudVolumeTracker.clearAll();
        this.volume.release();
        this.lastDimension = Integer.MIN_VALUE;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (this.failed || !this.initialized || mc.world == null || mc.player == null) {
            return;
        }
        World world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        int dimension = world.provider.getDimension();
        if (dimension != this.lastDimension) {
            this.lastDimension = dimension;
            this.volume.release();
        }

        this.updateFrameTiming(event);
        this.volume.update(dimension, REBUILD_BUDGET);
        if (!this.volume.isActive()) {
            return;
        }

        Vec3d cameraPos = ActiveRenderInfo.projectViewFromEntity(view, partialTicks);
        CameraBasis basis = this.readCameraBasis(mc, view, partialTicks);
        this.lastNearPlane = basis.nearPlane;
        this.lastFarPlane = basis.farPlane;

        float cellSize = this.volume.getCellSize();
        Vec3d volMin = new Vec3d(this.volume.getOriginX(), this.volume.getOriginY(), this.volume.getOriginZ());
        Vec3d volMax = new Vec3d(
                volMin.x + this.volume.getDimX() * (double) cellSize,
                volMin.y + this.volume.getDimY() * (double) cellSize,
                volMin.z + this.volume.getDimZ() * (double) cellSize);

        if (!this.isPotentiallyVisible(cameraPos, volMin, volMax, basis)) {
            return;
        }

        int width = mc.displayWidth;
        int height = mc.displayHeight;
        if (width <= 0 || height <= 0) {
            return;
        }

        int[] scissor = this.computeScissor(cameraPos, volMin, volMax, basis, width, height);

        this.resolveScale();
        this.targetW = Math.max(1, (int) (width * this.renderScale));
        this.targetH = Math.max(1, (int) (height * this.renderScale));

        boolean useFbo = this.renderScale < 0.98F;
        if (useFbo && !this.ensureCloudTarget(this.targetW, this.targetH)) {
            useFbo = false;
            this.targetW = width;
            this.targetH = height;
        }
        float scissorScale = (float) this.targetW / (float) width;

        if (this.depthTexture == -1 || this.depthWidth != width || this.depthHeight != height) {
            this.initDepthTexture(width, height);
        }

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.depthTexture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        boolean scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if (useFbo) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.cloudFbo);
                GL11.glViewport(0, 0, this.targetW, this.targetH);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
                GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
            } else {
                GL11.glViewport(0, 0, width, height);
            }

            if (scissor != null) {
                int sx = (int) (scissor[0] * scissorScale);
                int sy = (int) (scissor[1] * scissorScale);
                int sw = Math.max(1, (int) ((scissor[2] - scissor[0]) * scissorScale));
                int sh = Math.max(1, (int) ((scissor[3] - scissor[1]) * scissorScale));
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(sx, sy, sw, sh);
            }

            this.beginCloudTimer();
            this.renderVolumePass(cameraPos, basis, volMin, volMax, world, view, partialTicks);

            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (useFbo) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
                GL11.glViewport(0, 0, width, height);
                // Only the projected volume can contain cloud, so the composite
                // never has to touch the rest of the screen.
                if (scissor != null) {
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                    GL11.glScissor(scissor[0], scissor[1], scissor[2] - scissor[0], scissor[3] - scissor[1]);
                }
                this.compositeToScreen(this.targetW, this.targetH);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        } finally {
            this.endCloudTimer();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(0, 0, width, height);
            GL11.glScissor(0, 0, width, height);
            if (scissorWasOn) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(0);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.disableBlend();
            GlStateManager.enableCull();
            GlStateManager.enableAlpha();
        }
    }

    // ------------------------------------------------------------------
    // quality
    // ------------------------------------------------------------------

    private void updateFrameTiming(RenderWorldLastEvent event) {
        long now = System.nanoTime();
        if (this.lastFrameNanos != 0L) {
            float deltaMs = (now - this.lastFrameNanos) / 1_000_000.0F;
            if (deltaMs > 0.0F && deltaMs < 250.0F) {
                this.frameMsEma += (deltaMs - this.frameMsEma) * 0.05F;
            }
        }
        this.lastFrameNanos = now;
    }

    private void resolveScale() {
        switch (qualityMode) {
            case QUALITY_LOW:
                this.renderScale = 0.32F;
                break;
            case QUALITY_MEDIUM:
                this.renderScale = 0.45F;
                break;
            case QUALITY_HIGH:
                this.renderScale = 1.0F;
                break;
            default:
                this.adaptQuality();
                break;
        }
    }

    // ------------------------------------------------------------------
    // events
    // ------------------------------------------------------------------

    /**
     * Moves the render scale towards a comfortable frame time. Only reacts to
     * large deviations so that a vsync locked 60 fps never oscillates.
     *
     * <p>The cloud pass is the only thing this can trade against, so the targets
     * are deliberately conservative: on the reference Intel UHD a cloudy full
     * screen at 0.5 scale costs several milliseconds, which is the difference
     * between 110 and 60 fps in a scene that is otherwise GPU bound.</p>
     */
    private void adaptQuality() {
        this.framesSinceQualityChange++;
        if (this.framesSinceQualityChange < 30) {
            return;
        }
        if (this.hasGpuTimer() && this.gpuSamples > 40) {
            if (this.gpuMsEma > 2.2F && this.renderScale > MIN_SCALE) {
                this.renderScale = Math.max(MIN_SCALE, this.renderScale - 0.06F);
                this.framesSinceQualityChange = 0;
            } else if (this.gpuMsEma < 1.0F && this.renderScale < MAX_SCALE) {
                this.renderScale = Math.min(MAX_SCALE, this.renderScale + 0.04F);
                this.framesSinceQualityChange = 0;
            }
            return;
        }
        if (this.frameMsEma > 19.0F && this.renderScale > MIN_SCALE) {
            this.renderScale = Math.max(MIN_SCALE, this.renderScale - 0.06F);
            this.framesSinceQualityChange = 0;
        } else if (this.frameMsEma < 11.5F && this.renderScale < MAX_SCALE) {
            this.renderScale = Math.min(MAX_SCALE, this.renderScale + 0.04F);
            this.framesSinceQualityChange = 0;
        }
    }

    // ------------------------------------------------------------------
    // camera and volume helpers
    // ------------------------------------------------------------------

    private static final class CameraBasis {
        Vec3d forward;
        Vec3d right;
        Vec3d up;
        float tanHalfFov;
        float aspect;
        float nearPlane;
        float farPlane;
    }

    private CameraBasis readCameraBasis(Minecraft mc, Entity view, float partialTicks) {
        CameraBasis basis = new CameraBasis();

        Vec3d forward = null;
        Vec3d up = null;
        Vec3d right = null;
        try {
            // The modelview matrix is a WORLD -> EYE transform (MC draws world
            // space vertices with it), so the camera axes expressed in world
            // space are its *rows*: row0 = right, row1 = up, row2 = backward.
            // glGetFloat returns column major, hence indices 0/4/8, 1/5/9, 2/6/10.
            // Reading the columns instead (the transpose) mirrors the view
            // direction, which makes the cloud region swing the wrong way when
            // the camera turns.
            this.matrixBuffer.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, this.matrixBuffer);
            Vec3d candidateRight = new Vec3d(this.matrixBuffer.get(0), this.matrixBuffer.get(4), this.matrixBuffer.get(8));
            Vec3d candidateUp = new Vec3d(this.matrixBuffer.get(1), this.matrixBuffer.get(5), this.matrixBuffer.get(9));
            Vec3d candidateForward = new Vec3d(-this.matrixBuffer.get(2), -this.matrixBuffer.get(6), -this.matrixBuffer.get(10));
            if (candidateForward.lengthVector() > 0.5D && candidateUp.lengthVector() > 0.5D
                    && Math.abs(candidateForward.normalize().dotProduct(candidateUp.normalize())) < 0.5D) {
                forward = candidateForward.normalize();
                up = candidateUp.normalize();
                right = candidateRight.normalize();
            }
        } catch (Throwable ignored) {
            // fall through to the yaw/pitch reconstruction
        }

        if (forward == null) {
            float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partialTicks;
            float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partialTicks;
            if (mc.gameSettings.thirdPersonView == 2) {
                yaw += 180.0F;
                pitch = -pitch;
            }
            float yawRad = (float) Math.toRadians(yaw);
            float pitchRad = (float) Math.toRadians(pitch);
            float sinYaw = MathHelper.sin(yawRad);
            float cosYaw = MathHelper.cos(yawRad);
            float sinPitch = MathHelper.sin(pitchRad);
            float cosPitch = MathHelper.cos(pitchRad);
            forward = new Vec3d(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch).normalize();
            right = new Vec3d(-cosYaw, 0.0D, -sinYaw).normalize();
            up = right.crossProduct(forward).normalize();
        }

        float nearPlane = 0.05F;
        float farPlane = Math.max(64.0F, mc.gameSettings.renderDistanceChunks * 16.0F * MathHelper.SQRT_2);
        float tanHalfFov = (float) Math.tan(Math.toRadians(mc.gameSettings.fovSetting) * 0.5D);
        float aspect = (float) mc.displayWidth / (float) Math.max(1, mc.displayHeight);

        try {
            this.matrixBuffer.clear();
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, this.matrixBuffer);
            float p0 = this.matrixBuffer.get(0);
            float p5 = this.matrixBuffer.get(5);
            float p10 = this.matrixBuffer.get(10);
            float p11 = this.matrixBuffer.get(11);
            float p14 = this.matrixBuffer.get(14);
            if (p11 < -0.5F && p5 > 0.2F && Math.abs(p0) > 1.0E-4F) {
                tanHalfFov = 1.0F / p5;
                aspect = p5 / p0;
                float candidateNear = p14 / (p10 - 1.0F);
                float candidateFar = p14 / (p10 + 1.0F);
                if (candidateNear > 0.001F && candidateNear < 8.0F
                        && candidateFar > candidateNear * 4.0F && candidateFar < 65536.0F) {
                    nearPlane = candidateNear;
                    farPlane = candidateFar;
                }
            }
        } catch (Throwable ignored) {
            // keep the settings derived defaults
        }

        basis.forward = forward;
        basis.right = right;
        basis.up = up;
        basis.tanHalfFov = tanHalfFov;
        basis.aspect = aspect;
        basis.nearPlane = nearPlane;
        basis.farPlane = farPlane;
        return basis;
    }

    private boolean isPotentiallyVisible(Vec3d cameraPos, Vec3d volMin, Vec3d volMax, CameraBasis basis) {
        Vec3d center = volMin.add(volMax).scale(0.5D);
        Vec3d delta = center.subtract(cameraPos);
        double radius = volMax.subtract(volMin).lengthVector() * 0.5D;
        double distance = delta.lengthVector();
        if (distance > MAX_VOLUME_DISTANCE + radius) {
            return false;
        }
        return delta.dotProduct(basis.forward) + radius > 0.0D;
    }

    /**
     * Screen rectangle (in pixels, origin bottom left) that the volume can cover.
     * Returns {@code null} for "full screen".
     */
    private int[] computeScissor(Vec3d cameraPos, Vec3d volMin, Vec3d volMax, CameraBasis basis,
                                 int width, int height) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? volMin.x : volMax.x;
            double y = (corner & 2) == 0 ? volMin.y : volMax.y;
            double z = (corner & 4) == 0 ? volMin.z : volMax.z;
            Vec3d rel = new Vec3d(x, y, z).subtract(cameraPos);
            double viewZ = rel.dotProduct(basis.forward);
            if (viewZ <= basis.nearPlane) {
                return null;
            }
            double ndcX = rel.dotProduct(basis.right) / (viewZ * basis.tanHalfFov * basis.aspect);
            double ndcY = rel.dotProduct(basis.up) / (viewZ * basis.tanHalfFov);
            double px = (ndcX * 0.5D + 0.5D) * width;
            double py = (ndcY * 0.5D + 0.5D) * height;
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        // A little margin for the depth discontinuity filtering in the composite.
        int x0 = (int) Math.floor(minX) - 8;
        int y0 = (int) Math.floor(minY) - 8;
        int x1 = (int) Math.ceil(maxX) + 8;
        int y1 = (int) Math.ceil(maxY) + 8;
        x0 = Math.max(x0, 0);
        y0 = Math.max(y0, 0);
        x1 = Math.min(x1, width);
        y1 = Math.min(y1, height);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        if (x0 == 0 && y0 == 0 && x1 == width && y1 == height) {
            return null;
        }
        return new int[]{x0, y0, x1, y1};
    }

    // ------------------------------------------------------------------
    // passes
    // ------------------------------------------------------------------

    private void renderVolumePass(Vec3d cameraPos, CameraBasis basis, Vec3d volMin, Vec3d volMax,
                                  World world, Entity view, float partialTicks) {
        CloudShaderProgram program = this.volumeProgram;
        program.use();

        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.depthTexture);
        program.set1i("depthTex", 0);

        GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, this.volume.getFineTexture());
        program.set1i("densityTex", 1);

        float celestial = world.getCelestialAngle(partialTicks);
        float sunAngle = celestial * (float) Math.PI * 2.0F;
        Vec3d sunDir = new Vec3d(-MathHelper.sin(sunAngle), MathHelper.cos(sunAngle), 0.0D).normalize();
        float sunBrightness = world.getSunBrightness(partialTicks);
        float sunHeight = Math.max(0.0F, (float) sunDir.y);
        Vec3d sunColor;
        if (sunDir.y <= 0.0D) {
            sunColor = new Vec3d(0.0D, 0.0D, 0.0D);
        } else {
            Vec3d warm = new Vec3d(1.0D, 0.58D + 0.40D * sunHeight, 0.34D + 0.60D * sunHeight);
            sunColor = warm.scale(0.30D + 1.10D * sunBrightness);
        }

        Vec3d skyColor = world.getSkyColor(view, partialTicks);
        Vec3d fogColor = world.getFogColor(partialTicks);
        Vec3d cloudTint = world.getCloudColour(partialTicks);

        program.set3f("cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);
        program.set3f("camForward", basis.forward.x, basis.forward.y, basis.forward.z);
        program.set3f("camRight", basis.right.x, basis.right.y, basis.right.z);
        program.set3f("camUp", basis.up.x, basis.up.y, basis.up.z);
        program.set3f("sunDir", sunDir.x, sunDir.y, sunDir.z);
        program.set3f("sunColor", sunColor.x, sunColor.y, sunColor.z);
        program.set3f("skyZenith", skyColor.x, skyColor.y, skyColor.z);
        program.set3f("fogColor", fogColor.x, fogColor.y, fogColor.z);
        program.set3f("cloudTint", cloudTint.x, cloudTint.y, cloudTint.z);
        program.set3f("volOrigin", volMin.x, volMin.y, volMin.z);
        program.set3f("volSize", volMax.x - volMin.x, volMax.y - volMin.y, volMax.z - volMin.z);
        program.set1f("tanHalfFov", basis.tanHalfFov);
        program.set1f("aspect", basis.aspect);
        program.set1f("nearPlane", basis.nearPlane);
        program.set1f("farPlane", basis.farPlane);
        program.set1f("fogStart", basis.farPlane * 0.72F);
        program.set1f("fogEnd", basis.farPlane * 0.99F);
        program.set1f("extinction", extinction);
        program.set1f("detailAmount", detailAmount);
        program.set1f("detailScale", detailScale);
        program.set1f("isoLow", isoLow);
        program.set1f("isoHigh", isoHigh);
        program.set1f("sunSoftness", sunSoftness);
        // Drift in seconds. Wrapped to a bounded window: the value feeds a float
        // shader uniform, and an unbounded total world time would lose the
        // fractional drift to float precision after a long lived world (the
        // noise coordinate would advance in visible steps instead of smoothly).
        float time = ((world.getTotalWorldTime() % 260_000L) + partialTicks) * 0.05F;
        program.set1f("time", time);
        program.set1i("stepCount", stepCount());

        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        // The shader writes premultiplied colour, which is also what the
        // half resolution buffer expects (it is cleared to zero, so blending
        // there is a no-op and one code path covers both targets).
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();

        this.drawFullScreenQuad(false);

        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        CloudShaderProgram.stop();
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    private void compositeToScreen(int width, int height) {
        CloudShaderProgram program = this.compositeProgram;
        program.use();

        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.cloudColorTex);
        program.set1i("cloudTex", 0);
        program.set2f("cloudTexel", 1.0F / width, 1.0F / height);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        // Vanilla keeps GL_ALPHA_TEST enabled with GL_GREATER/0.1 here; that
        // would cut off exactly the soft, partially transparent cloud edge this
        // pass exists to produce.
        GlStateManager.disableAlpha();

        this.drawFullScreenQuad(true);

        GlStateManager.enableAlpha();
        CloudShaderProgram.stop();
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    private void drawFullScreenQuad(boolean textured) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glBegin(GL11.GL_QUADS);
        if (textured) {
            GL11.glTexCoord2f(0.0F, 0.0F);
        }
        GL11.glVertex2f(-1.0F, -1.0F);
        if (textured) {
            GL11.glTexCoord2f(1.0F, 0.0F);
        }
        GL11.glVertex2f(1.0F, -1.0F);
        if (textured) {
            GL11.glTexCoord2f(1.0F, 1.0F);
        }
        GL11.glVertex2f(1.0F, 1.0F);
        if (textured) {
            GL11.glTexCoord2f(0.0F, 1.0F);
        }
        GL11.glVertex2f(-1.0F, 1.0F);
        GL11.glEnd();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------
    // GL targets
    // ------------------------------------------------------------------

    private void initDepthTexture(int width, int height) {
        if (this.depthTexture == -1) {
            this.depthTexture = GL11.glGenTextures();
        }
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.depthTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        this.depthWidth = width;
        this.depthHeight = height;
    }

    private boolean ensureCloudTarget(int width, int height) {
        if (this.cloudFbo != -1 && this.cloudWidth == width && this.cloudHeight == height) {
            return true;
        }
        if (this.cloudFbo == -1) {
            this.cloudFbo = GL30.glGenFramebuffers();
            this.cloudColorTex = GL11.glGenTextures();
        }
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.cloudColorTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.cloudFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.cloudColorTex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[CloudVolumeRenderer] cloud framebuffer incomplete: " + status);
            if (this.cloudFbo != -1) {
                GL30.glDeleteFramebuffers(this.cloudFbo);
                this.cloudFbo = -1;
            }
            if (this.cloudColorTex != -1) {
                GL11.glDeleteTextures(this.cloudColorTex);
                this.cloudColorTex = -1;
            }
            this.cloudWidth = -1;
            this.cloudHeight = -1;
            return false;
        }
        this.cloudWidth = width;
        this.cloudHeight = height;
        return true;
    }

    public void dispose() {
        this.volume.release();
        if (this.depthTexture != -1) {
            GL11.glDeleteTextures(this.depthTexture);
            this.depthTexture = -1;
        }
        if (this.cloudColorTex != -1) {
            GL11.glDeleteTextures(this.cloudColorTex);
            this.cloudColorTex = -1;
        }
        if (this.cloudFbo != -1) {
            GL30.glDeleteFramebuffers(this.cloudFbo);
            this.cloudFbo = -1;
        }
        if (this.volumeProgram != null) {
            this.volumeProgram.dispose();
        }
        if (this.compositeProgram != null) {
            this.compositeProgram.dispose();
        }
    }

    /** Called by {@code /cloud rescan}. */
    public static void requestRescan(World world) {
        if (world == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int chunkX = 0;
        int chunkZ = 0;
        if (mc.player != null) {
            chunkX = MathHelper.floor(mc.player.posX) >> 4;
            chunkZ = MathHelper.floor(mc.player.posZ) >> 4;
        }
        int radius = Math.max(4, mc.gameSettings.renderDistanceChunks + 2);
        CloudVolumeTracker.requestRescan(world, world.provider.getDimension(), chunkX, chunkZ, radius);
    }
}
