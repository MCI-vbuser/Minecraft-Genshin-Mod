package com.vbuser.genshin.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

public class CloudVolumeRenderer {

    // ================== 配置 ==================
    private static final boolean HALF_RESOLUTION     = true;
    private static final int     STEP_COUNT_HALF     = 10;     // 半分辨率步数
    private static final int     STEP_COUNT_FULL     = 16;     // 全分辨率步数
    private static final float   NEAR_PLANE          = 0.05f;

    // ================== 体积云着色器 ==================
    private static final String VOLUME_VERT_SRC =
            "#version 120\n" +
                    "varying vec2 vUv;\n" +
                    "void main() {\n" +
                    "    vUv = gl_Vertex.xy * 0.5 + 0.5;\n" +
                    "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n" +
                    "}\n";

    private static final String VOLUME_FRAG_SRC =
            "#version 120\n" +
                    "uniform sampler2D depthTex;\n" +
                    "uniform vec3 cameraPos;\n" +
                    "uniform vec3 minBound;\n" +
                    "uniform vec3 maxBound;\n" +
                    "uniform vec3 camForward;\n" +
                    "uniform vec3 camRight;\n" +
                    "uniform vec3 camUp;\n" +
                    "uniform vec3 sunDir;\n" +
                    "uniform vec3 sunColor;\n" +
                    "uniform vec3 skyColor;\n" +
                    "uniform vec3 noiseOffset;\n" +
                    "uniform float tanHalfFov;\n" +
                    "uniform float aspect;\n" +
                    "uniform float nearPlane;\n" +
                    "uniform float farPlane;\n" +
                    "uniform float baseDensity;\n" +
                    "uniform float time;\n" +
                    "uniform int   stepCount;\n" +
                    "varying vec2 vUv;\n" +
                    "\n" +
                    "// 完全兼容 GLSL 120 的 4x4 Bayer 矩阵抖动\n" +
                    "float bayer4(vec2 uv) {\n" +
                    "    vec2 p = floor(mod(uv, 4.0));\n" +
                    "    int x = int(p.x);\n" +
                    "    int y = int(p.y);\n" +
                    "    int idx = x + y * 4;\n" +
                    "    float v = 0.0;\n" +
                    "    if (idx == 0) v = 0.0;  else if (idx == 1) v = 8.0;\n" +
                    "    else if (idx == 2) v = 2.0;  else if (idx == 3) v = 10.0;\n" +
                    "    else if (idx == 4) v = 12.0; else if (idx == 5) v = 4.0;\n" +
                    "    else if (idx == 6) v = 14.0; else if (idx == 7) v = 6.0;\n" +
                    "    else if (idx == 8) v = 3.0;  else if (idx == 9) v = 11.0;\n" +
                    "    else if (idx == 10) v = 1.0; else if (idx == 11) v = 9.0;\n" +
                    "    else if (idx == 12) v = 15.0; else if (idx == 13) v = 7.0;\n" +
                    "    else if (idx == 14) v = 13.0; else if (idx == 15) v = 5.0;\n" +
                    "    return v / 16.0;\n" +
                    "}\n" +
                    "\n" +
                    "float hash(vec3 p) {\n" +
                    "    p = fract(p * vec3(0.1031, 0.1030, 0.0973));\n" +
                    "    p += dot(p, p.yxz + 33.33);\n" +
                    "    return fract((p.x + p.y) * p.z);\n" +
                    "}\n" +
                    "\n" +
                    "float noise(vec3 p) {\n" +
                    "    vec3 i = floor(p);\n" +
                    "    vec3 f = fract(p);\n" +
                    "    f = f * f * (3.0 - 2.0 * f);\n" +
                    "    return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x),\n" +
                    "                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),\n" +
                    "               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),\n" +
                    "                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z) * 2.0 - 1.0;\n" +
                    "}\n" +
                    "\n" +
                    "float fbm(vec3 p) {\n" +
                    "    float value = 0.0;\n" +
                    "    float amplitude = 0.5;\n" +
                    "    for (int i = 0; i < 2; i++) {\n" +
                    "        value += amplitude * noise(p);\n" +
                    "        p *= 2.0;\n" +
                    "        amplitude *= 0.5;\n" +
                    "    }\n" +
                    "    return value;\n" +
                    "}\n" +
                    "\n" +
                    "float density(vec3 p) {\n" +
                    "    vec3 dmin = p - minBound;\n" +
                    "    vec3 dmax = maxBound - p;\n" +
                    "    float inside = min(min(dmin.x, min(dmin.y, dmin.z)), min(dmax.x, min(dmax.y, dmax.z)));\n" +
                    "    if (inside <= 0.0) return 0.0;\n" +
                    "    float edge = smoothstep(0.0, 16.0, inside);\n" +
                    "\n" +
                    "    vec3 q = p + noiseOffset;\n" +
                    "    vec3 warp = vec3(\n" +
                    "        fbm(q * 0.06),\n" +
                    "        fbm(q * 0.06 + vec3(13.4, 42.1, 91.2)),\n" +
                    "        fbm(q * 0.06 + vec3(4.1, 7.3, 1.8))\n" +
                    "    ) * 8.0;\n" +
                    "    q += warp;\n" +
                    "\n" +
                    "    float n = fbm(q * vec3(0.04, 0.05, 0.04));\n" +
                    "    n = n * 0.5 + 0.5;\n" +
                    "\n" +
                    "    float t = clamp((p.y - minBound.y) / max(maxBound.y - minBound.y, 0.1), 0.0, 1.0);\n" +
                    "    float h = smoothstep(0.0, 0.2, t) * (1.0 - smoothstep(0.8, 1.0, t));\n" +
                    "    float weighted = n * h;\n" +
                    "\n" +
                    "    float threshold = 0.35;\n" +
                    "    if (weighted < threshold) return 0.0;\n" +
                    "    return smoothstep(threshold, 0.75, weighted) * edge;\n" +
                    "}\n" +
                    "\n" +
                    "bool intersectBox(vec3 ro, vec3 rd, vec3 bmin, vec3 bmax, out float tMin, out float tMax) {\n" +
                    "    vec3 invR = 1.0 / rd;\n" +
                    "    vec3 t0 = (bmin - ro) * invR;\n" +
                    "    vec3 t1 = (bmax - ro) * invR;\n" +
                    "    vec3 tsmall = min(t0, t1);\n" +
                    "    vec3 tbig = max(t0, t1);\n" +
                    "    tMin = max(tsmall.x, max(tsmall.y, tsmall.z));\n" +
                    "    tMax = min(tbig.x, min(tbig.y, tbig.z));\n" +
                    "    return tMax >= max(tMin, 0.0);\n" +
                    "}\n" +
                    "\n" +
                    "float phaseHG(float cosTheta, float g) {\n" +
                    "    float g2 = g * g;\n" +
                    "    return (1.0 - g2) / (4.0 * 3.14159265 * pow(max(1.0 + g2 - 2.0 * g * cosTheta, 0.0001), 1.5));\n" +
                    "}\n" +
                    "\n" +
                    "float phaseDualLobe(float cosTheta) {\n" +
                    "    return mix(phaseHG(cosTheta, 0.7), phaseHG(cosTheta, -0.2), 0.5);\n" +
                    "}\n" +
                    "\n" +
                    "vec3 atmosphericSunColor(float sunHeight) {\n" +
                    "    float t = clamp(sunHeight, 0.0, 1.0);\n" +
                    "    vec3 sunset = vec3(1.0, 0.30, 0.10);\n" +
                    "    vec3 noon   = vec3(1.0, 0.98, 0.92);\n" +
                    "    vec3 base = mix(sunset, noon, smoothstep(0.0, 0.45, t));\n" +
                    "    float below = smoothstep(0.0, -0.15, sunHeight);\n" +
                    "    base = mix(base, vec3(0.28, 0.30, 0.45), below);\n" +
                    "    float intensity = smoothstep(-0.15, 0.05, sunHeight);\n" +
                    "    return base * mix(0.15, 1.0, intensity);\n" +
                    "}\n" +
                    "\n" +
                    "void main() {\n" +
                    "    float sceneDepth = texture2D(depthTex, vUv).r;\n" +
                    "    if (sceneDepth <= 0.0 || sceneDepth >= 1.0) sceneDepth = 1.0;\n" +
                    "    vec2 ndc = vUv * 2.0 - 1.0;\n" +
                    "    vec3 rd = normalize(camForward + camRight * ndc.x * tanHalfFov * aspect + camUp * ndc.y * tanHalfFov);\n" +
                    "    float ndcZ = sceneDepth * 2.0 - 1.0;\n" +
                    "    float zVis = 2.0 * nearPlane * farPlane / (farPlane + nearPlane - ndcZ * (farPlane - nearPlane));\n" +
                    "    float tScene = zVis / max(dot(rd, camForward), 0.0001);\n" +
                    "\n" +
                    "    vec3 ro = cameraPos;\n" +
                    "    float tMin, tMax;\n" +
                    "    if (!intersectBox(ro, rd, minBound, maxBound, tMin, tMax)) discard;\n" +
                    "    float tStart = max(tMin, 0.0);\n" +
                    "    float tEnd = min(tMax, tScene);\n" +
                    "    if (tEnd <= tStart) discard;\n" +
                    "\n" +
                    "    float sigma = 0.5;\n" +
                    "    float transmittance = 1.0;\n" +
                    "    vec3 radiance = vec3(0.0);\n" +
                    "    float cosTheta = dot(rd, sunDir);\n" +
                    "    float phase = phaseDualLobe(cosTheta);\n" +
                    "    vec3 sunCol = atmosphericSunColor(sunDir.y) * sunColor;\n" +
                    "    \n" +
                    "    vec3 ambientLight = skyColor * 0.35;\n" +
                    "\n" +
                    "    float jitter = bayer4(gl_FragCoord.xy);\n" +
                    "    float dt = (tEnd - tStart) / float(stepCount);\n" +
                    "    float t = tStart + jitter * dt;\n" +
                    "    int emptySteps = 0;\n" +
                    "\n" +
                    "    for (int i = 0; i < 32; i++) {\n" +
                    "        if (i >= stepCount || t > tEnd) break;\n" +
                    "        vec3 pos = ro + rd * t;\n" +
                    "        float dens = density(pos) * baseDensity;\n" +
                    "        \n" +
                    "        if (dens > 0.01) {\n" +
                    "            emptySteps = 0;\n" +
                    "            float stepT = exp(-sigma * dens * dt);\n" +
                    "            \n" +
                    "            float sunDepth = density(pos + sunDir * 5.0) * 5.0;\n" +
                    "            float sunT = exp(-sunDepth * 1.5);\n" +
                    "            float msScatter = exp(-sunDepth * 0.2);\n" +
                    "            \n" +
                    "            vec3 lightCol = sunCol * (phase * 1.5 + 0.2) * (sunT * 0.75 + msScatter * 0.25) + ambientLight;\n" +
                    "            radiance += transmittance * (1.0 - stepT) * lightCol;\n" +
                    "            transmittance *= stepT;\n" +
                    "        } else {\n" +
                    "            emptySteps++;\n" +
                    "            if (emptySteps >= 2) {\n" +
                    "                dt *= 1.5;\n" +
                    "                emptySteps = 0;\n" +
                    "            }\n" +
                    "        }\n" +
                    "        if (i > 3 && transmittance < 0.15) break;\n" +
                    "        if (transmittance < 0.05) break;\n" +
                    "        t += dt;\n" +
                    "    }\n" +
                    "\n" +
                    "    float distFade = exp(-tStart * 0.0015);\n" +
                    "    float alpha = (1.0 - transmittance) * distFade;\n" +
                    "    vec3 color = vec3(1.0) - exp(-radiance * 2.2);\n" +
                    "    gl_FragColor = vec4(color * alpha, alpha);\n" +
                    "}\n";

    // ================== 合成着色器（带深度感知上采样） ==================
    private static final String COMP_VERT_SRC =
            "#version 120\n" +
                    "void main() {\n" +
                    "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "}\n";

    private static final String COMP_FRAG_SRC =
            "#version 120\n" +
                    "uniform sampler2D cloudTex;\n" +
                    "uniform sampler2D depthTex;\n" +
                    "uniform vec2 invTargetSize;\n" +
                    "void main() {\n" +
                    "    vec2 uv = gl_TexCoord[0].xy;\n" +
                    "    float centerDepth = texture2D(depthTex, uv).r;\n" +
                    "    \n" +
                    "    vec4 col = vec4(0.0);\n" +
                    "    float weightSum = 0.0;\n" +
                    "    \n" +
                    "    for (int y = -1; y <= 1; y += 2) {\n" +
                    "        for (int x = -1; x <= 1; x += 2) {\n" +
                    "            vec2 offset = vec2(float(x), float(y)) * invTargetSize;\n" +
                    "            vec2 sampleUv = uv + offset;\n" +
                    "            float d = texture2D(depthTex, sampleUv).r;\n" +
                    "            // 防止全 0 导致 NaN\n" +
                    "            float w = max(exp(-abs(d - centerDepth) * 1000.0), 0.001);\n" +
                    "            col += texture2D(cloudTex, sampleUv) * w;\n" +
                    "            weightSum += w;\n" +
                    "        }\n" +
                    "    }\n" +
                    "    gl_FragColor = col / max(weightSum, 0.0001);\n" +
                    "}\n";

    // ================== GL 资源 ==================
    private int volumeProgram = 0;
    private int compositeProgram = 0;

    private int depthTexture = -1;
    private int depthWidth = -1;
    private int depthHeight = -1;

    private int cloudFbo = -1;
    private int cloudColorTex = -1;
    private int cloudFboW = -1;
    private int cloudFboH = -1;

    private long seedUsed = Long.MIN_VALUE;
    private float noiseOffsetX, noiseOffsetY, noiseOffsetZ;

    private int uDepthTex, uCameraPos, uMinBound, uMaxBound,
            uCamForward, uCamRight, uCamUp, uSunDir,
            uSunColor, uSkyColor, uNoiseOffset, uTanHalfFov,
            uAspect, uNearPlane, uFarPlane, uBaseDensity, uTime,
            uStepCount;

    private int uCompCloudTex, uCompDepthTex, uCompInvSize;

    private final ArrayDeque<ChunkPos> scanQueue = new ArrayDeque<>();
    private final Map<ChunkPos, Integer> activeScans = new HashMap<>();
    int scanSectionsPerTick = 2;

    public CloudVolumeRenderer() {
        try {
            volumeProgram = loadProgram(VOLUME_VERT_SRC, VOLUME_FRAG_SRC);
            compositeProgram = loadProgram(COMP_VERT_SRC, COMP_FRAG_SRC);

            uDepthTex      = GL20.glGetUniformLocation(volumeProgram, "depthTex");
            uCameraPos     = GL20.glGetUniformLocation(volumeProgram, "cameraPos");
            uMinBound      = GL20.glGetUniformLocation(volumeProgram, "minBound");
            uMaxBound      = GL20.glGetUniformLocation(volumeProgram, "maxBound");
            uCamForward    = GL20.glGetUniformLocation(volumeProgram, "camForward");
            uCamRight      = GL20.glGetUniformLocation(volumeProgram, "camRight");
            uCamUp         = GL20.glGetUniformLocation(volumeProgram, "camUp");
            uSunDir        = GL20.glGetUniformLocation(volumeProgram, "sunDir");
            uSunColor      = GL20.glGetUniformLocation(volumeProgram, "sunColor");
            uSkyColor      = GL20.glGetUniformLocation(volumeProgram, "skyColor");
            uNoiseOffset   = GL20.glGetUniformLocation(volumeProgram, "noiseOffset");
            uTanHalfFov    = GL20.glGetUniformLocation(volumeProgram, "tanHalfFov");
            uAspect        = GL20.glGetUniformLocation(volumeProgram, "aspect");
            uNearPlane     = GL20.glGetUniformLocation(volumeProgram, "nearPlane");
            uFarPlane      = GL20.glGetUniformLocation(volumeProgram, "farPlane");
            uBaseDensity   = GL20.glGetUniformLocation(volumeProgram, "baseDensity");
            uTime          = GL20.glGetUniformLocation(volumeProgram, "time");
            uStepCount     = GL20.glGetUniformLocation(volumeProgram, "stepCount");

            uCompCloudTex  = GL20.glGetUniformLocation(compositeProgram, "cloudTex");
            uCompDepthTex  = GL20.glGetUniformLocation(compositeProgram, "depthTex");
            uCompInvSize   = GL20.glGetUniformLocation(compositeProgram, "invTargetSize");
        } catch (Exception e) {
            System.err.println("[CloudVolumeRenderer] Shader initialization failed!");
            volumeProgram = 0;
            compositeProgram = 0;
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || volumeProgram == 0) return;

        int dim = mc.world.provider.getDimension();
        BlockPos min = CloudVolumeTracker.getMin(dim);
        BlockPos max = CloudVolumeTracker.getMax(dim);
        if (min == null || max == null) return;

        ensureNoiseOffset(mc.world.getSeed());

        int width  = mc.displayWidth;
        int height = mc.displayHeight;

        if (depthTexture == -1 || depthWidth != width || depthHeight != height) {
            initDepthTexture(width, height);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(depthTexture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        Entity view = mc.getRenderViewEntity();
        if (view == null) return;

        float partialTicks = event.getPartialTicks();
        Vec3d cameraPos = ActiveRenderInfo.getCameraPosition().add(view.getPositionVector());

        float yaw   = view.prevRotationYaw   + (view.rotationYaw   - view.prevRotationYaw)   * partialTicks;
        float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partialTicks;
        if (mc.gameSettings.thirdPersonView == 2) {
            yaw += 180.0F;
            pitch = -pitch;
        }

        float yawRad   = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float sinYaw = MathHelper.sin(yawRad);
        float cosYaw = MathHelper.cos(yawRad);
        float sinPitch = MathHelper.sin(pitchRad);
        float cosPitch = MathHelper.cos(pitchRad);

        Vec3d forward = new Vec3d(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
        Vec3d right   = new Vec3d(-cosYaw, 0, -sinYaw);
        Vec3d up      = right.crossProduct(forward);

        float tanHalfFov = (float) Math.tan(Math.toRadians(mc.gameSettings.fovSetting) / 2.0);
        float aspect     = (float) width / (float) height;

        float celestial    = mc.world.getCelestialAngle(partialTicks);
        float sunAngleRad  = celestial * (float) Math.PI * 2.0F;
        Vec3d sunDir = new Vec3d(-MathHelper.sin(sunAngleRad), MathHelper.cos(sunAngleRad), 0.0D).normalize();

        float dayFactor = mc.world.getSunBrightness(partialTicks);
        float sunHeight = (float) sunDir.y;

        Vec3d sunColor = sunHeight < 0.0F
                ? new Vec3d(0.30F, 0.34F, 0.42F)
                : new Vec3d(1.0F, 0.96F + 0.04F * Math.min(1.0F, sunHeight * 1.2F), 0.90F + 0.10F * Math.min(1.0F, sunHeight * 1.2F));
        sunColor = sunColor.scale(0.55F + dayFactor * 0.75F);

        Vec3d skyColor;
        if (sunHeight < 0.2F) {
            float t = MathHelper.clamp((sunHeight + 0.1F) / 0.3F, 0F, 1F);
            Vec3d sunsetTint = new Vec3d(0.85, 0.45, 0.32);
            Vec3d dayTint    = new Vec3d(0.60, 0.68, 0.75);
            skyColor = sunsetTint.scale(1 - t).add(dayTint.scale(t));
        } else {
            skyColor = new Vec3d(0.60, 0.68, 0.75);
        }
        skyColor = skyColor.scale(0.45 + dayFactor * 0.55);

        float avgDensity  = CloudVolumeTracker.getAverageDensity(dim);
        float baseDensity = 0.5f + (avgDensity / 15.0f) * 0.4f;
        float time        = (mc.world.getTotalWorldTime() + partialTicks) % 3600.0f;
        float farPlane    = mc.gameSettings.renderDistanceChunks * 16.0f;

        int targetW = width;
        int targetH = height;
        int stepCount = STEP_COUNT_FULL;
        boolean useHalf = HALF_RESOLUTION;

        if (useHalf) {
            int halfW = Math.max(1, width  / 2);
            int halfH = Math.max(1, height / 2);
            if (cloudFbo == -1 || cloudFboW != halfW || cloudFboH != halfH) {
                initCloudFbo(halfW, halfH);
            }
            if (cloudFbo != -1) {
                targetW = halfW;
                targetH = halfH;
                stepCount = STEP_COUNT_HALF;
            } else {
                useHalf = false;
            }
        }

        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        if (useHalf) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, cloudFbo);
            GL11.glViewport(0, 0, targetW, targetH);
            GlStateManager.clearColor(0f, 0f, 0f, 0f);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
        } else {
            GL11.glViewport(0, 0, width, height);
        }

        renderVolumePass(cameraPos, forward, right, up, min, max, sunDir, sunColor, skyColor,
                tanHalfFov, aspect, farPlane, baseDensity, time, stepCount);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL11.glViewport(0, 0, width, height);
        if (useHalf) {
            compositeCloudToScreen(targetW, targetH);
        }
    }

    private void renderVolumePass(Vec3d cameraPos, Vec3d forward, Vec3d right, Vec3d up,
                                  BlockPos min, BlockPos max,
                                  Vec3d sunDir, Vec3d sunColor, Vec3d skyColor,
                                  float tanHalfFov, float aspect,
                                  float farPlane,
                                  float baseDensity, float time, int stepCount) {
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(depthTexture);

        GL20.glUseProgram(volumeProgram);
        GL20.glUniform1i(uDepthTex, 0);
        GL20.glUniform3f(uCameraPos, (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        GL20.glUniform3f(uMinBound, min.getX(), min.getY(), min.getZ());
        GL20.glUniform3f(uMaxBound, max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        GL20.glUniform3f(uCamForward, (float) forward.x, (float) forward.y, (float) forward.z);
        GL20.glUniform3f(uCamRight,   (float) right.x,   (float) right.y,   (float) right.z);
        GL20.glUniform3f(uCamUp,      (float) up.x,      (float) up.y,      (float) up.z);
        GL20.glUniform3f(uSunDir,     (float) sunDir.x,  (float) sunDir.y,  (float) sunDir.z);
        GL20.glUniform3f(uSunColor,   (float) sunColor.x,(float) sunColor.y,(float) sunColor.z);
        GL20.glUniform3f(uSkyColor,   (float) skyColor.x,(float) skyColor.y,(float) skyColor.z);
        GL20.glUniform3f(uNoiseOffset, noiseOffsetX, noiseOffsetY, noiseOffsetZ);
        GL20.glUniform1f(uTanHalfFov, tanHalfFov);
        GL20.glUniform1f(uAspect,     aspect);
        GL20.glUniform1f(uNearPlane, CloudVolumeRenderer.NEAR_PLANE);
        GL20.glUniform1f(uFarPlane,   farPlane);
        GL20.glUniform1f(uBaseDensity, baseDensity);
        GL20.glUniform1f(uTime,       time);
        GL20.glUniform1i(uStepCount,  stepCount);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1.0f, -1.0f);
        GL11.glVertex2f( 1.0f, -1.0f);
        GL11.glVertex2f( 1.0f,  1.0f);
        GL11.glVertex2f(-1.0f,  1.0f);
        GL11.glEnd();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL20.glUseProgram(0);
    }

    private void compositeCloudToScreen(int cloudW, int cloudH) {
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(cloudColorTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GlStateManager.bindTexture(depthTexture);

        GL20.glUseProgram(compositeProgram);
        GL20.glUniform1i(uCompCloudTex, 0);
        GL20.glUniform1i(uCompDepthTex, 1);
        GL20.glUniform2f(uCompInvSize, 1.0f / cloudW, 1.0f / cloudH);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, -1f);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f( 1f, -1f);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f( 1f,  1f);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(-1f,  1f);
        GL11.glEnd();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();

        GL20.glUseProgram(0);

        // 恢复纹理单元状态
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GlStateManager.bindTexture(0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
    }

    private void initDepthTexture(int width, int height) {
        if (depthTexture == -1) {
            depthTexture = GL11.glGenTextures();
        }
        GlStateManager.bindTexture(depthTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        depthWidth  = width;
        depthHeight = height;
    }

    private void initCloudFbo(int w, int h) {
        if (cloudFbo == -1) {
            cloudFbo      = GL30.glGenFramebuffers();
            cloudColorTex = GL11.glGenTextures();
        }

        GlStateManager.bindTexture(cloudColorTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, cloudFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, cloudColorTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[CloudVolumeRenderer] FBO Creation failed! Status: " + status);
            if (cloudFbo != -1) { GL30.glDeleteFramebuffers(cloudFbo); cloudFbo = -1; }
            if (cloudColorTex != -1) { GL11.glDeleteTextures(cloudColorTex); cloudColorTex = -1; }
            return;
        }
        cloudFboW = w;
        cloudFboH = h;
    }

    private int loadProgram(String vertSrc, String fragSrc) {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, vertSrc);
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 4096);
            throw new RuntimeException("Cloud shader link error: " + log);
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return program;
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            throw new RuntimeException("Cloud shader compile error: " + log);
        }
        return shader;
    }

    private void ensureNoiseOffset(long seed) {
        if (seedUsed == seed) return;
        seedUsed = seed;
        Random random = new Random(seed);
        noiseOffsetX = random.nextFloat() * 10000;
        noiseOffsetY = random.nextFloat() * 10000;
        noiseOffsetZ = random.nextFloat() * 10000;
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if (!world.isRemote) return;
        ChunkPos cp = event.getChunk().getPos();
        if (!scanQueue.contains(cp) && !activeScans.containsKey(cp)) {
            scanQueue.add(cp);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        World world = event.getWorld();
        if (!world.isRemote) return;
        ChunkPos cp = event.getChunk().getPos();
        scanQueue.remove(cp);
        activeScans.remove(cp);
        CloudVolumeTracker.removeChunk(world.provider.getDimension(), cp);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            CloudVolumeTracker.clearDimension(event.getWorld().provider.getDimension());
            scanQueue.clear();
            activeScans.clear();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        processPendingScans();
    }

    private void processPendingScans() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            scanQueue.clear();
            activeScans.clear();
            return;
        }
        while (!scanQueue.isEmpty()) {
            ChunkPos cp = scanQueue.poll();
            activeScans.put(cp, 0);
        }
        Iterator<Map.Entry<ChunkPos, Integer>> it = activeScans.entrySet().iterator();
        int budget = scanSectionsPerTick;
        while (it.hasNext() && budget > 0) {
            Map.Entry<ChunkPos, Integer> entry = it.next();
            ChunkPos cp = entry.getKey();
            int section = entry.getValue();
            Chunk chunk = mc.world.getChunkProvider().getLoadedChunk(cp.x, cp.z);
            if (chunk == null) {
                it.remove();
                continue;
            }
            CloudVolumeTracker.scanChunkSection(mc.world, chunk, section);
            budget--;
            int next = section + 1;
            if (next >= 16) {
                it.remove();
            } else {
                entry.setValue(next);
            }
        }
    }
}