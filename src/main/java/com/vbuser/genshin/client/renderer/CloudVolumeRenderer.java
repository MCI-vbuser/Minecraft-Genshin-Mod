package com.vbuser.genshin.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
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

    private static final String VERTEX_SRC =
            "#version 120\n" +
                    "attribute vec3 position;\n" +
                    "varying vec2 vUv;\n" +
                    "void main() {\n" +
                    "    vUv = position.xy * 0.5 + 0.5;\n" +
                    "    gl_Position = vec4(position.xy, 1.0, 1.0);\n" +
                    "}\n";

    private static final String FRAG_SRC =
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
                    "varying vec2 vUv;\n" +
                    "\n" +
                    "float hash(vec3 p) {\n" +
                    "    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));\n" +
                    "    p *= 17.0;\n" +
                    "    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));\n" +
                    "}\n" +
                    "\n" +
                    "float noise(vec3 p) {\n" +
                    "    vec3 i = floor(p);\n" +
                    "    vec3 f = fract(p);\n" +
                    "    f = f * f * (3.0 - 2.0 * f);\n" +
                    "    float n000 = hash(i);\n" +
                    "    float n100 = hash(i + vec3(1.0, 0.0, 0.0));\n" +
                    "    float n010 = hash(i + vec3(0.0, 1.0, 0.0));\n" +
                    "    float n110 = hash(i + vec3(1.0, 1.0, 0.0));\n" +
                    "    float n001 = hash(i + vec3(0.0, 0.0, 1.0));\n" +
                    "    float n101 = hash(i + vec3(1.0, 0.0, 1.0));\n" +
                    "    float n011 = hash(i + vec3(0.0, 1.0, 1.0));\n" +
                    "    float n111 = hash(i + vec3(1.0, 1.0, 1.0));\n" +
                    "    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),\n" +
                    "                mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z) * 2.0 - 1.0;\n" +
                    "}\n" +
                    "\n" +
                    "float fbm(vec3 p) {\n" +
                    "    float value = 0.0;\n" +
                    "    float amplitude = 0.5;\n" +
                    "    float frequency = 1.0;\n" +
                    "    float maxVal = 0.0;\n" +
                    "    for (int i = 0; i < 4; i++) {\n" +
                    "        value += amplitude * noise(p * frequency);\n" +
                    "        maxVal += amplitude;\n" +
                    "        amplitude *= 0.5;\n" +
                    "        frequency *= 2.0;\n" +
                    "    }\n" +
                    "    return value / maxVal;\n" +
                    "}\n" +
                    "\n" +
                    "float density(vec3 p) {\n" +
                    "    vec3 dmin = p - minBound;\n" +
                    "    vec3 dmax = maxBound - p;\n" +
                    "    float inside = min(min(dmin.x, min(dmin.y, dmin.z)), min(dmax.x, min(dmax.y, dmax.z)));\n" +
                    "    if (inside <= 0.0) return 0.0;\n" +
                    "    float edge = smoothstep(0.0, 8.0, inside);\n" +
                    "\n" +
                    "    vec3 q = p + noiseOffset;\n" +
                    "    float macro = fbm(q * vec3(0.055, 0.022, 0.055));\n" +
                    "    float detail = noise(q * vec3(0.28, 0.10, 0.28)) * 0.12;\n" +
                    "    float shape = macro + detail;\n" +
                    "    float n = clamp(shape * 0.5 + 0.5, 0.0, 1.0);\n" +
                    "\n" +
                    "    float t = clamp((p.y - minBound.y) / max(maxBound.y - minBound.y, 0.1), 0.0, 1.0);\n" +
                    "    float bottom = smoothstep(0.0, 0.18, t);\n" +
                    "    float top = 1.0 - smoothstep(0.55, 0.88, t);\n" +
                    "    float h = bottom * top;\n" +
                    "    float weighted = n * h;\n" +
                    "\n" +
                    "    float threshold = 0.22;\n" +
                    "    if (weighted < threshold) return 0.0;\n" +
                    "    float mapped = smoothstep(threshold, 1.0, weighted);\n" +
                    "    mapped = pow(mapped, 0.85);\n" +
                    "    return mapped * edge;\n" +
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
                    "float sunOpticalDepth(float localDensity, vec3 pos, float sigma) {\n" +
                    "    float tMin, tMax;\n" +
                    "    if (!intersectBox(pos, sunDir, minBound, maxBound, tMin, tMax)) return 0.0;\n" +
                    "    float dist = max(tMax, 0.0);\n" +
                    "    return sigma * localDensity * dist * 0.4;\n" +
                    "}\n" +
                    "\n" +
                    "void main() {\n" +
                    "    float sceneDepth = texture2D(depthTex, vUv).r;\n" +
                    "    if (sceneDepth <= 0.0 || sceneDepth >= 1.0) sceneDepth = 1.0;\n" +
                    "\n" +
                    "    vec2 ndc = vUv * 2.0 - 1.0;\n" +
                    "    vec3 rd = normalize(camForward + camRight * ndc.x * tanHalfFov * aspect + camUp * ndc.y * tanHalfFov);\n" +
                    "\n" +
                    "    float ndcZ = sceneDepth * 2.0 - 1.0;\n" +
                    "    float zVis = 2.0 * nearPlane * farPlane / (farPlane + nearPlane - ndcZ * (farPlane - nearPlane));\n" +
                    "    float tScene = zVis / max(dot(rd, camForward), 0.0001);\n" +
                    "\n" +
                    "    vec3 ro = cameraPos;\n" +
                    "    float tMin, tMax;\n" +
                    "    if (!intersectBox(ro, rd, minBound, maxBound, tMin, tMax)) discard;\n" +
                    "\n" +
                    "    float tStart = max(tMin, 0.0);\n" +
                    "    float tEnd = min(tMax, tScene);\n" +
                    "    if (tEnd <= tStart) discard;\n" +
                    "\n" +
                    "    int stepCount = 10;\n" +
                    // ---- 距离 LOD：远处使用更大步长，减少实际采样次数 ----
                    "    float distToCloud = tStart;\n" +
                    "    float lodStepScale = mix(1.0, 2.0, smoothstep(40.0, 160.0, distToCloud));\n" +
                    "    float dt = (tEnd - tStart) / float(stepCount) * lodStepScale;\n" +
                    // -------------------------------------------------
                    "    float sigma = 0.65;\n" +
                    "    float transmittance = 1.0;\n" +
                    "    vec3 radiance = vec3(0.0);\n" +
                    "    float cosTheta = dot(rd, sunDir);\n" +
                    "\n" +
                    "    for (int i = 0; i < stepCount; i++) {\n" +
                    "        float t = tStart + (float(i) + 0.5) * dt;\n" +
                    "        if (t > tEnd) break;\n" +
                    "        vec3 pos = ro + rd * t;\n" +
                    "        float dens = density(pos) * baseDensity;\n" +
                    "        if (dens > 0.001) {\n" +
                    "            float sunDepth = sunOpticalDepth(dens, pos, sigma);\n" +
                    "            float sunT = exp(-sigma * sunDepth);\n" +
                    "            float stepT = exp(-sigma * dens * dt);\n" +
                    "            float phase = phaseHG(cosTheta, 0.55);\n" +
                    "            float phaseBack = phaseHG(cosTheta, -0.25);\n" +
                    "            vec3 ambientLight = vec3(0.7, 0.75, 0.8) * 0.45;\n" +
                    "            float ambientShadow = mix(0.35, 1.0, sunT);\n" +
                    "            vec3 scatter = sunColor * (sunT * phase * 2.5 + (1.0 - sunT) * phaseBack * 0.35)\n" +
                    "                          + skyColor * (0.2 + 0.8 * sunT) * ambientShadow\n" +
                    "                          + ambientLight * ambientShadow;\n" +
                    "            radiance += transmittance * (1.0 - stepT) * scatter;\n" +
                    "            transmittance *= stepT;\n" +
                    "        }\n" +
                    "        if (transmittance < 0.03) break;\n" +
                    "    }\n" +
                    "\n" +
                    "    float alpha = 1.0 - transmittance;\n" +
                    "    vec3 color = vec3(1.0) - exp(-radiance * 2.0);\n" +
                    // ---- 预乘 alpha 输出，修复边缘色彩断层 ----
                    "    gl_FragColor = vec4(color * alpha, alpha);\n" +
                    // -----------------------------------------
                    "}\n";

    private int program;
    private int depthTexture = -1;
    private int depthWidth = -1;
    private int depthHeight = -1;

    private long seedUsed = Long.MIN_VALUE;
    private float noiseOffsetX, noiseOffsetY, noiseOffsetZ;

    private int uniformDepthTex;
    private int uniformCameraPos;
    private int uniformMinBound;
    private int uniformMaxBound;
    private int uniformCamForward;
    private int uniformCamRight;
    private int uniformCamUp;
    private int uniformSunDir;
    private int uniformSunColor;
    private int uniformSkyColor;
    private int uniformNoiseOffset;
    private int uniformTanHalfFov;
    private int uniformAspect;
    private int uniformNearPlane;
    private int uniformFarPlane;
    private int uniformBaseDensity;

    private final ArrayDeque<ChunkPos> scanQueue = new ArrayDeque<>();
    private final Map<ChunkPos, Integer> activeScans = new HashMap<>();
    int scanSectionsPerTick = 2;

    public CloudVolumeRenderer() {
        try {
            program = loadProgram();
            uniformDepthTex = GL20.glGetUniformLocation(program, "depthTex");
            uniformCameraPos = GL20.glGetUniformLocation(program, "cameraPos");
            uniformMinBound = GL20.glGetUniformLocation(program, "minBound");
            uniformMaxBound = GL20.glGetUniformLocation(program, "maxBound");
            uniformCamForward = GL20.glGetUniformLocation(program, "camForward");
            uniformCamRight = GL20.glGetUniformLocation(program, "camRight");
            uniformCamUp = GL20.glGetUniformLocation(program, "camUp");
            uniformSunDir = GL20.glGetUniformLocation(program, "sunDir");
            uniformSunColor = GL20.glGetUniformLocation(program, "sunColor");
            uniformSkyColor = GL20.glGetUniformLocation(program, "skyColor");
            uniformNoiseOffset = GL20.glGetUniformLocation(program, "noiseOffset");
            uniformTanHalfFov = GL20.glGetUniformLocation(program, "tanHalfFov");
            uniformAspect = GL20.glGetUniformLocation(program, "aspect");
            uniformNearPlane = GL20.glGetUniformLocation(program, "nearPlane");
            uniformFarPlane = GL20.glGetUniformLocation(program, "farPlane");
            uniformBaseDensity = GL20.glGetUniformLocation(program, "baseDensity");
        } catch (Exception e) {
            program = 0;
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;
        if (program == 0) return;

        int dim = mc.world.provider.getDimension();
        BlockPos min = CloudVolumeTracker.getMin(dim);
        BlockPos max = CloudVolumeTracker.getMax(dim);
        if (min == null || max == null) return;

        ensureNoiseOffset(mc.world.getSeed());

        int width = mc.displayWidth;
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

        Vec3d cameraPos = ActiveRenderInfo.getCameraPosition()
                .add(view.getPositionVector());

        float yaw = view.prevRotationYaw
                + (view.rotationYaw - view.prevRotationYaw) * partialTicks;
        float pitch = view.prevRotationPitch
                + (view.rotationPitch - view.prevRotationPitch) * partialTicks;

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

        Vec3d forward = new Vec3d(
                -sinYaw * cosPitch,
                -sinPitch,
                cosYaw * cosPitch
        );

        Vec3d right = new Vec3d(
                -cosYaw,
                0,
                -sinYaw
        );

        Vec3d up = right.crossProduct(forward);

        float fov = mc.gameSettings.fovSetting;
        float tanHalfFov = (float) Math.tan(Math.toRadians(fov) / 2.0);
        float aspect = (float) width / (float) height;

        float celestial = mc.world.getCelestialAngle(partialTicks);
        float sunAngleRad = celestial * (float) Math.PI * 2.0F;
        Vec3d sunDir = new Vec3d(
                -MathHelper.sin(sunAngleRad),
                MathHelper.cos(sunAngleRad),
                0.0D
        ).normalize();

        float dayFactor = mc.world.getSunBrightness(partialTicks);
        float sunHeight = (float) sunDir.y;
        Vec3d sunColor;
        if (sunHeight < 0.0F) {
            sunColor = new Vec3d(0.30F, 0.34F, 0.42F);
        } else {
            float t = Math.min(1.0F, sunHeight * 1.2F);
            sunColor = new Vec3d(1.0F, 0.96F + 0.04F * t, 0.90F + 0.10F * t);
        }
        sunColor = sunColor.scale(0.55F + dayFactor * 0.75F);

        Vec3d skyColor = new Vec3d(0.60F, 0.68F, 0.75F).scale(0.45F + dayFactor * 0.55F);

        float avgDensity = CloudVolumeTracker.getAverageDensity(dim);
        float baseDensity = 0.60f + (avgDensity / 15.0f) * 0.8f;

        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        // ---- 改用预乘 alpha 混合 ----
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();

        GL20.glUseProgram(program);
        GL20.glUniform1i(uniformDepthTex, 0);
        GL20.glUniform3f(uniformCameraPos, (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        GL20.glUniform3f(uniformMinBound, min.getX(), min.getY(), min.getZ());
        GL20.glUniform3f(uniformMaxBound, max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        GL20.glUniform3f(uniformCamForward, (float) forward.x, (float) forward.y, (float) forward.z);
        GL20.glUniform3f(uniformCamRight, (float) right.x, (float) right.y, (float) right.z);
        GL20.glUniform3f(uniformCamUp, (float) up.x, (float) up.y, (float) up.z);
        GL20.glUniform3f(uniformSunDir, (float) sunDir.x, (float) sunDir.y, (float) sunDir.z);
        GL20.glUniform3f(uniformSunColor, (float) sunColor.x, (float) sunColor.y, (float) sunColor.z);
        GL20.glUniform3f(uniformSkyColor, (float) skyColor.x, (float) skyColor.y, (float) skyColor.z);
        GL20.glUniform3f(uniformNoiseOffset, noiseOffsetX, noiseOffsetY, noiseOffsetZ);
        GL20.glUniform1f(uniformTanHalfFov, tanHalfFov);
        GL20.glUniform1f(uniformAspect, aspect);
        GL20.glUniform1f(uniformNearPlane, 0.05f);
        GL20.glUniform1f(uniformFarPlane, mc.gameSettings.renderDistanceChunks * 16.0f);
        GL20.glUniform1f(uniformBaseDensity, baseDensity);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buffer.pos(-1, -1, 0).endVertex();
        buffer.pos( 1, -1, 0).endVertex();
        buffer.pos( 1,  1, 0).endVertex();
        buffer.pos(-1,  1, 0).endVertex();
        tessellator.draw();

        GL20.glUseProgram(0);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
    }

    private void ensureNoiseOffset(long seed) {
        if (seedUsed == seed) return;
        seedUsed = seed;
        Random random = new Random(seed);
        noiseOffsetX = random.nextFloat() * 10000;
        noiseOffsetY = random.nextFloat() * 10000;
        noiseOffsetZ = random.nextFloat() * 10000;
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
        depthWidth = width;
        depthHeight = height;
    }

    private int loadProgram() {
        int vs = compileShader(GL20.GL_VERTEX_SHADER, CloudVolumeRenderer.VERTEX_SRC);
        int fs = compileShader(GL20.GL_FRAGMENT_SHADER, CloudVolumeRenderer.FRAG_SRC);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Cloud shader link error: " + GL20.glGetProgramInfoLog(program, 4096));
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
            throw new RuntimeException("Cloud shader compile error: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
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