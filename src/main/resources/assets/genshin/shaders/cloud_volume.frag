#version 120
//
// Volumetric cloud raymarch.
//
// The cloud shape is a 3D density texture that the CPU bakes from the cloud
// blocks (one texel cell = one or more blocks), so the rendered volume matches
// the blocks that are actually placed, and every texel fetch replaces the
// ~80 hash() calls per step that the old procedural shader needed.
//
// Quality comes from three cheap additions on top of the block field:
//   * three octaves of value noise erode the isosurface, faded out with
//     distance (this is the LOD: no visible alias shimmer far away, full
//     detail up close),
//   * four density taps towards the sun give self shadowing,
//   * a coarse max-density grid skips provably empty boxes in one step while
//     the surface hunt inside a possibly occupied box never steps over a fine
//     texel, so a thin layer is neither missed nor integrated with a long step.
//

uniform sampler2D depthTex;
uniform sampler3D densityTex;
// Coarse max-density grid: MAX_GROUP fine texels per texel, one byte each.
uniform sampler3D densityMaxTex;

uniform vec3 cameraPos;
uniform vec3 camForward;
uniform vec3 camRight;
uniform vec3 camUp;
uniform vec3 sunDir;
uniform vec3 sunColor;
uniform vec3 skyZenith;
uniform vec3 fogColor;
uniform vec3 cloudTint;

uniform vec3 volOrigin;
uniform vec3 volSize;
// Geometry of the coarse grid: texel edge in blocks and grid size in texels.
uniform vec3 maxCellBlocks;
uniform vec3 maxDim;
// Size of one fine texel in blocks (the volume's LOD cell size).
uniform float fineCellBlocks;

uniform float tanHalfFov;
uniform float aspect;
uniform float nearPlane;
uniform float farPlane;
uniform float fogStart;
uniform float fogEnd;

uniform float extinction;
uniform float detailAmount;
uniform float detailScale;
uniform float isoLow;
uniform float isoHigh;
uniform float time;
uniform float sunSoftness;

uniform int stepCount;

varying vec2 vUv;

const float PI = 3.14159265;

// ----------------------------------------------------------------------------
// noise
// ----------------------------------------------------------------------------

float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// value noise in [-1, 1]
float vnoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = p - i;
    f = f * f * (3.0 - 2.0 * f);

    float n000 = hash13(i);
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));

    float x00 = mix(n000, n100, f.x);
    float x10 = mix(n010, n110, f.x);
    float x01 = mix(n001, n101, f.x);
    float x11 = mix(n011, n111, f.x);
    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z) * 2.0 - 1.0;
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// 4x4 Bayer, GLSL 120 has no lookup tables. Kept for reference / comparison with
// the hash: ordered dithering is correlated between neighbours but leaves a
// visible lattice on grazing surfaces.
float bayer4(vec2 fragCoord) {
    vec2 p = floor(mod(fragCoord, 4.0));
    int idx = int(p.x) + int(p.y) * 4;
    float v = 0.0;
    if (idx == 0) v = 0.0;  else if (idx == 1) v = 8.0;
    else if (idx == 2) v = 2.0;  else if (idx == 3) v = 10.0;
    else if (idx == 4) v = 12.0; else if (idx == 5) v = 4.0;
    else if (idx == 6) v = 14.0; else if (idx == 7) v = 6.0;
    else if (idx == 8) v = 3.0;  else if (idx == 9) v = 11.0;
    else if (idx == 10) v = 1.0; else if (idx == 11) v = 9.0;
    else if (idx == 12) v = 15.0; else if (idx == 13) v = 7.0;
    else if (idx == 14) v = 13.0; else if (idx == 15) v = 5.0;
    return v / 16.0;
}

// ----------------------------------------------------------------------------
// volume
// ----------------------------------------------------------------------------

// Density of the block field at a world position, 0..1.
float sampleField(vec3 p) {
    vec3 uvw = (p - volOrigin) / volSize;
    if (min(min(uvw.x, uvw.y), uvw.z) < 0.0 || max(max(uvw.x, uvw.y), uvw.z) > 1.0) {
        return 0.0;
    }
    return texture3D(densityTex, uvw).r;
}

// Maximum block-field density inside the coarse box that contains p. A value
// of exactly 0 proves that the whole box is empty, which is what makes the
// empty-space skip below exact instead of a guess.
float sampleCellMax(vec3 p) {
    vec3 uvw = (p - volOrigin) / (maxCellBlocks * maxDim);
    if (min(min(uvw.x, uvw.y), uvw.z) < 0.0 || max(max(uvw.x, uvw.y), uvw.z) > 1.0) {
        return 0.0;
    }
    return texture3D(densityMaxTex, uvw).r;
}

// Distance along rd from p to the far face of the coarse box containing p.
// The ray only ever jumps to a box boundary, so the next density sample is
// still taken inside the next box and no cloud can hide behind the jump.
float coarseCellExit(vec3 p, vec3 rd, vec3 safeRd) {
    vec3 local = (p - volOrigin) / maxCellBlocks;
    vec3 cell = floor(local);
    vec3 face = cell + step(vec3(0.0), rd);
    vec3 boundary = face * maxCellBlocks + volOrigin;
    vec3 dist = (boundary - p) / safeRd;
    return max(min(min(dist.x, dist.y), dist.z), 0.0);
}

// Detailed density: the block field plus noise erosion, faded with distance.
//
// The noise only matters inside a narrow band around the isosurface: if the
// block field is further from it than the noise amplitude can move it, the
// result is provably 1 or 0 and the three noise octaves (24 hash calls, the
// most expensive part of a sample) can be skipped. This is exact, not an
// approximation, and it is what makes a screen filled with cloud affordable.
float cloudDensity(vec3 p, float dist) {
    float base = sampleField(p);
    if (base <= 0.0) {
        return 0.0;
    }

    float band = detailAmount * 1.15;
    if (base >= isoHigh + band) {
        return 1.0;
    }
    if (base <= isoLow - band) {
        return 0.0;
    }

    float wMid = 1.0 - smoothstep(120.0, 420.0, dist);
    float wFine = 1.0 - smoothstep(30.0, 130.0, dist);

    vec3 q = p * detailScale + vec3(time * 0.075, time * -0.022, time * 0.040);
    float n = vnoise(q) * 0.62;
    if (wMid > 0.01) {
        n += vnoise(q * 2.63 + vec3(19.3, 7.1, 31.7)) * 0.30 * wMid;
    }
    if (wFine > 0.01) {
        n += vnoise(q * 7.31 + vec3(5.7, 23.9, 11.3)) * 0.15 * wFine;
    }

    return smoothstep(isoLow, isoHigh, base + n * detailAmount);
}

// Optical depth towards the sun, 3 taps of the smooth field. Each tap is a
// texture fetch, so the tap count is the single biggest cost per lit sample
// (measured: 4 taps -> 3 cuts the cloud-filled-screen case by ~20%). The taps
// integrate roughly the first 30 blocks, which is where the shadowing that is
// visible from outside the cloud comes from.
float sunOpticalDepth(vec3 p) {
    float od = sampleField(p + sunDir * 2.5) * 2.5;
    od += sampleField(p + sunDir * 11.0) * 8.5;
    od += sampleField(p + sunDir * 30.0) * 19.0;
    return od;
}

float phaseHG(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * pow(max(denom, 1.0e-4), 1.5));
}

// Scattering phase normalised so an isotropic medium returns 1.0, then biased
// heavily towards isotropic: single scattering alone makes clouds almost black
// whenever the sun is behind the camera, while real cumulus stay bright thanks
// to multiple scattering. The forward lobe is what gives the silver lining.
float phaseCloud(float cosTheta) {
    float dual = mix(phaseHG(cosTheta, 0.55), phaseHG(cosTheta, -0.2), 0.35) * (4.0 * PI);
    return mix(1.0, dual, 0.6);
}

vec3 shade(vec3 p, float dens, float phase, vec3 ambient) {
    float tau = sunOpticalDepth(p) * extinction * sunSoftness;
    float sunT = exp(-tau);
    // Cheap stand in for multiple scattering: a much shallower extinction
    // curve, which is what keeps thick cloud cores luminous instead of black.
    float msT = exp(-tau * 0.38);
    float powder = 1.0 - exp(-dens * 3.0);

    vec3 sun = sunColor * phase;

    return sun * (sunT * 0.65 + msT * 0.35) * (0.62 + 0.38 * powder)
            + ambient * (0.72 + 0.28 * powder);
}

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    vec3 rd = normalize(camForward
            + camRight * ndc.x * tanHalfFov * aspect
            + camUp * ndc.y * tanHalfFov);

    float tScene = 1.0e9;
    float sceneDepth = texture2D(depthTex, vUv).r;
    if (sceneDepth < 1.0) {
        float ndcZ = sceneDepth * 2.0 - 1.0;
        float viewZ = 2.0 * nearPlane * farPlane
                / (farPlane + nearPlane - ndcZ * (farPlane - nearPlane));
        tScene = viewZ / max(dot(rd, camForward), 1.0e-4);
    }

    vec3 bmin = volOrigin;
    vec3 bmax = volOrigin + volSize;
    vec3 safeR = sign(rd + vec3(1.0e-7)) * max(abs(rd), vec3(1.0e-7));
    vec3 invR = 1.0 / safeR;
    vec3 ta = (bmin - cameraPos) * invR;
    vec3 tb = (bmax - cameraPos) * invR;
    vec3 tsmall = min(ta, tb);
    vec3 tbig = max(ta, tb);
    float tNear = max(max(tsmall.x, tsmall.y), tsmall.z);
    float tFar = min(min(tbig.x, tbig.y), tbig.z);

    float tStart = max(tNear, 0.0);
    float tEnd = min(tFar, tScene);
    // Bound the marched distance: anything beyond this is already blended into
    // the fog, and a ray grazing along a cloud layer would otherwise march
    // hundreds of blocks of cloud for no visible gain.
    tEnd = min(tEnd, tStart + 280.0);
    if (tEnd <= tStart) {
        discard;
    }

    float span = tEnd - tStart;
    // Spacing used once the surface has been found. This is the quality knob:
    // it trades sampling cost for surface smoothness, not for thin layer
    // visibility, because the hunt below never steps over a fine texel.
    float dtFine = clamp(span * 0.5 / float(stepCount), 0.6, 4.0);
    // Step while looking for the surface inside a box that may contain cloud:
    // at most one fine texel, so a one texel thick layer can not fall between
    // two samples. With a coarse LOD cell the texel is larger, hence the max().
    float huntStep = max(fineCellBlocks, min(dtFine, 1.0));
    // Only real density samples are budgeted; skipping a proven empty box is a
    // single fetch and must not consume the ray's quality budget.
    int sampleBudget = stepCount * 2;

    // Dither the ray start per pixel. Interleaved gradient noise: continuous like
    // the hash (no dot lattice) but with a smooth spatial falloff, so neighbouring
    // pixels shift by a similar amount and the integral stays calm. This is the
    // pattern that reads best on grazing silhouettes; a per *step* jitter is what
    // must be avoided, because it decorrelates the quadrature and turns the cloud
    // into grain.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    // Jitter by the actual surface-hunt interval. Using the larger interior
    // integration step here can skip a one-texel layer at the volume boundary.
    float t = tStart + jitter * huntStep;
    float stepLen = huntStep;

    float transmittance = 1.0;
    vec3 radiance = vec3(0.0);
    float hitT = -1.0;

    float cosTheta = dot(rd, sunDir);
    float phase = phaseCloud(cosTheta);
    vec3 ambient = skyZenith * 0.45 + cloudTint * 0.10;

    // The bound must be at least the largest stepCount the renderer can send,
    // otherwise the tail of the ray is never marched and far clouds would end
    // in a hard line.
    int samples = 0;
    bool inCloud = false;
    for (int i = 0; i < 256; i++) {
        if (t >= tEnd || transmittance < 0.02 || samples >= sampleBudget) {
            break;
        }
        vec3 p = cameraPos + rd * t;
        // Probing is only useful while hunting for the surface; inside a cloud
        // the fine step and the transmittance bound the work already.
        if (!inCloud && sampleCellMax(p) <= 0.0) {
            // The coarse max proves this whole box is empty: jump straight to
            // its far face. Nothing can be stepped over, because the next
            // sample is still inside the next box.
            t += max(coarseCellExit(p, rd, safeR), 1.0e-3) + 1.0e-3;
            stepLen = dtFine;
            continue;
        }
        stepLen = inCloud ? dtFine : huntStep;
        float dens = cloudDensity(p, t);
        samples++;
        if (dens > 0.004) {
            float sigma = extinction * dens;
            float stepT = exp(-sigma * stepLen);
            radiance += transmittance * (1.0 - stepT) * shade(p, dens, phase, ambient);
            transmittance *= stepT;
            if (hitT < 0.0 && transmittance < 0.5) {
                hitT = t;
            }
            inCloud = true;
        } else {
            inCloud = false;
        }
        t += stepLen;
    }

    float alpha = 1.0 - transmittance;
    if (alpha <= 0.002) {
        discard;
    }

    vec3 color = 1.0 - exp(-radiance * 1.15);

    // Aerial perspective, so the volume edge melts into the sky instead of
    // ending in a visible wall.
    float hitDist = hitT > 0.0 ? hitT : (tStart + tEnd) * 0.5;
    vec3 horizon = mix(fogColor, skyZenith, clamp(rd.y, 0.0, 1.0) * 0.55);
    float fogT = clamp((hitDist - fogStart) / max(fogEnd - fogStart, 1.0), 0.0, 1.0);
    color = mix(color, horizon, fogT);
    alpha *= 1.0 - fogT * 0.85;

    gl_FragColor = vec4(color * alpha, alpha);
}
