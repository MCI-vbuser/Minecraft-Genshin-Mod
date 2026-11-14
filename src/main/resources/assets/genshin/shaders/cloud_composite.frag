#version 120
//
// Upsample of the reduced resolution cloud buffer: a 2 texel tent over the
// cloud texture, nothing else.
//
// History matters here. Three attempts tried to use the scene depth in this
// pass and all of them were wrong in the same way: the only depth available is
// the *scene* depth, not the cloud's, so the test cannot ask "is this cloud
// behind that surface". Fading by a depth ratio painted every terrain edge into
// the cloud; a binary reject cut blocks of legitimate cloud wherever the terrain
// depth varied between neighbouring texels. Occlusion is already handled
// correctly inside the raymarch (each ray stops at the depth of its own pixel),
// so the upsample must not second guess it.
//
// The tent itself is what kills the raymarch dither: four taps cover four dither
// phases, so the pattern averages out instead of being stretched over the screen.
//

uniform sampler2D cloudTex;
uniform vec2 cloudTexel;

void main() {
    vec2 uv = gl_TexCoord[0].xy;
    vec2 offset = cloudTexel * 0.5;

    vec4 cloud = 0.25 * (
            texture2D(cloudTex, uv + vec2(offset.x, offset.y))
            + texture2D(cloudTex, uv + vec2(-offset.x, offset.y))
            + texture2D(cloudTex, uv + vec2(offset.x, -offset.y))
            + texture2D(cloudTex, uv + vec2(-offset.x, -offset.y)));
    if (cloud.a <= 0.0005) {
        discard;
    }
    gl_FragColor = cloud;
}
