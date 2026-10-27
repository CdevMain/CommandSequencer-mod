#version 150

// CommandSequencer "Screen Effect+" pass. Every effect has its own strength (0..1) so they can be combined.
uniform sampler2D Sampler0;
uniform float Time;
uniform vec2 ScreenSize;
uniform float Dizzy;
uniform float Sleep;
uniform float Heat;
uniform float Cold;
uniform float Insanity;
uniform float Poison;
uniform float Invert;
uniform float Melt;
uniform float Gray;
uniform float Blood;
uniform float BloodTime;

in vec2 texCoord;
out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

vec3 tex(vec2 uv) { return texture(Sampler0, clamp(uv, vec2(0.001), vec2(0.999))).rgb; }

vec3 hueShift(vec3 c, float a) {
    const vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

void main() {
    vec2 uv = texCoord;
    float t = Time;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);

    // Melting: columns drip down at different speeds.
    float meltShade = 0.0;
    if (Melt > 0.0) {
        float col = floor(uv.x * 90.0);
        float r = hash(vec2(col, 7.0));
        float drip = Melt * (0.03 + 0.32 * r * r) * (0.85 + 0.15 * sin(t * 0.8 + r * 6.283));
        drip += Melt * 0.03 * noise(vec2(uv.x * 30.0, t * 0.5));
        uv.y += drip;
        uv.x += sin(uv.y * 25.0 + t * 1.5) * 0.004 * Melt;
        meltShade = smoothstep(0.98, 1.15, uv.y);
    }
    // Heat shimmer, stronger near the ground.
    if (Heat > 0.0) {
        float k = Heat * (0.4 + 0.6 * (1.0 - texCoord.y));
        uv.x += sin(uv.y * 70.0 + t * 7.0) * 0.0025 * k + (noise(vec2(uv.y * 40.0, t * 2.0)) - 0.5) * 0.004 * k;
        uv.y += cos(uv.x * 55.0 + t * 5.0) * 0.0018 * k;
    }
    // Dizziness / poison wobble.
    float wob = Dizzy * 0.012 + Poison * 0.006;
    if (wob > 0.0) uv += vec2(sin(t * 1.3 + uv.y * 6.0), cos(t * 1.1 + uv.x * 5.0)) * wob;
    // Insanity: horizontal glitch bands.
    if (Insanity > 0.0) {
        float frame = floor(t * 12.0);
        float g = step(0.93 - Insanity * 0.12, hash(vec2(frame, floor(uv.y * 24.0))));
        uv.x += g * (hash(vec2(frame, 3.0)) - 0.5) * 0.10 * Insanity;
        // displaced rectangular blocks
        vec2 cell = floor(uv * vec2(8.0, 14.0));
        float b = step(0.965 - Insanity * 0.05, hash(cell + frame * 1.37));
        uv += b * (vec2(hash(cell + 11.0), hash(cell + 17.0)) - 0.5) * 0.12 * Insanity;
    }

    vec3 c;
    if (Insanity > 0.0) {
        vec2 d = vec2(0.004 + 0.008 * abs(sin(t * 2.3)), 0.0) * Insanity;
        c = vec3(tex(uv + d).r, tex(uv).g, tex(uv - d).b);
    } else {
        c = tex(uv);
    }
    if (Dizzy > 0.0) {
        vec2 off = vec2(sin(t * 0.9), cos(t * 0.7)) * 0.015 * Dizzy;
        c = mix(c, tex(uv + off), 0.45 * Dizzy);
        vec2 px = vec2(2.0) / ScreenSize * (1.0 + 2.0 * Dizzy);
        vec3 b = (tex(uv + vec2(px.x, 0.0)) + tex(uv - vec2(px.x, 0.0)) + tex(uv + vec2(0.0, px.y)) + tex(uv - vec2(0.0, px.y))) * 0.25;
        c = mix(c, b, 0.5 * Dizzy);
    }

    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    vec2 cuv = texCoord - 0.5;
    cuv.x *= aspect;
    float edge = length(cuv);

    if (Heat > 0.0) {
        c = mix(c, c * vec3(1.18, 0.93, 0.72) + vec3(0.06, 0.02, 0.0), Heat * 0.75);
        c += vec3(0.35, 0.12, 0.0) * Heat * smoothstep(0.35, 0.0, texCoord.y) * (0.6 + 0.4 * sin(t * 2.0));
    }
    if (Cold > 0.0) {
        c = mix(c, vec3(lum) * vec3(0.82, 0.95, 1.15) + vec3(0.0, 0.02, 0.06), Cold * 0.65);
        vec2 fp = texCoord * vec2(aspect, 1.0);
        float n = noise(fp * 9.0) * 0.5 + noise(fp * 27.0) * 0.3 + noise(fp * 80.0) * 0.2;
        float frost = smoothstep(0.62 - 0.12 * Cold, 0.9, edge * 1.05 + (n - 0.5) * 0.3);
        c = mix(c, vec3(0.88, 0.96, 1.0), frost * Cold * 0.85);
    }
    if (Poison > 0.0) {
        float pulse = 0.65 + 0.35 * sin(t * 2.2);
        c = mix(c, c * vec3(0.65, 1.12, 0.55) + vec3(0.0, 0.05, 0.0), Poison * 0.7);
        c = mix(c, vec3(0.12, 0.45, 0.05), smoothstep(0.35, 0.95, edge) * Poison * pulse * 0.8);
    }
    if (Insanity > 0.0) {
        // scanlines and dropped lines
        c *= 1.0 - 0.18 * Insanity * step(0.5, fract(texCoord.y * ScreenSize.y * 0.25));
        float drop = step(0.985, hash(vec2(floor(t * 20.0), floor(texCoord.y * 90.0))));
        c = mix(c, vec3(hash(vec2(t, texCoord.y)) > 0.5 ? 1.0 : 0.0), drop * Insanity);
        c = mix(c, hueShift(c, t * 0.8), Insanity * 0.6);
        c = mix(c, vec3(0.5, 0.0, 0.05), smoothstep(0.3, 1.0, edge) * Insanity * (0.5 + 0.5 * sin(t * 5.0)));
        c += (hash(texCoord * ScreenSize + vec2(t)) - 0.5) * 0.15 * Insanity;
        float fl = step(0.97, hash(vec2(floor(t * 8.0), 9.0)));
        c = mix(c, vec3(1.0) - c, fl * Insanity * 0.8);
    }
    if (Gray > 0.0) c = mix(c, vec3(dot(c, vec3(0.299, 0.587, 0.114))), Gray);
    if (Invert > 0.0) c = mix(c, vec3(1.0) - c, Invert);
    if (Melt > 0.0) c = mix(c, c * 0.35, meltShade * Melt);
    // Blood: a smeared rim at the top and edges and drips running down over time.
    if (Blood > 0.0) {
        float fromTop = 1.0 - texCoord.y;
        float cols = 64.0;
        float col = floor(texCoord.x * cols);
        float r = hash(vec2(col, 3.1)), r2 = hash(vec2(col, 8.7));
        float fx = fract(texCoord.x * cols) - 0.5;
        float len = min(1.1, BloodTime * (0.02 + 0.09 * r)) * (0.25 + 0.75 * r2) * Blood;
        float w = (0.18 + 0.32 * r2) * (1.0 - 0.45 * smoothstep(0.0, max(len, 0.001), fromTop));
        float drip = step(0.45, r2 + 0.25 * r) * step(abs(fx), w) * step(fromTop, len);
        float head = step(0.45, r2 + 0.25 * r) * step(length(vec2(fx * 1.2, (fromTop - len) * cols * 0.35)), w * 1.25);
        float rim = smoothstep(0.10 * Blood + 0.06 * noise(vec2(texCoord.x * 18.0, 2.0)), 0.0, fromTop);
        float splat = smoothstep(0.6, 0.98, edge + (noise(texCoord * vec2(aspect, 1.0) * 7.0) - 0.5) * 0.45) * Blood;
        float amount = clamp(max(max(drip, head), max(rim * Blood, splat * 0.85)), 0.0, 1.0);
        vec3 blood = vec3(0.42, 0.0, 0.015) * (0.65 + 0.35 * noise(texCoord * vec2(aspect, 1.0) * 70.0));
        blood += vec3(0.25, 0.05, 0.05) * pow(max(0.0, 1.0 - abs(fx) / max(w, 0.001)), 6.0) * drip; // wet highlight
        c = mix(c, c * vec3(1.0, 0.78, 0.78), Blood * 0.35);
        c = mix(c, blood, amount * 0.92);
    }
    // Sleep: eyelids closing with a slow blink.
    if (Sleep > 0.0) {
        float close = clamp(Sleep * (0.8 + 0.2 * sin(t * 1.3)), 0.0, 1.0);
        float x = (texCoord.x - 0.5) * 2.0;
        float open = (1.0 - close) * (1.0 - 0.45 * x * x);
        float d = abs(texCoord.y - 0.5) * 2.0;
        float lid = smoothstep(open - 0.12, open + 0.02, d);
        c *= (1.0 - 0.35 * Sleep);
        c = mix(c, vec3(0.0), max(lid, step(0.999, close)));
        c = mix(c, vec3(0.0), smoothstep(0.4, 1.0, edge) * Sleep * 0.6);
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
