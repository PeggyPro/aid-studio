export const LIQUID_ORB_VERTEX_SHADER = `
varying vec2 vUv;

void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.0, 1.0);
}
`

export const LIQUID_ORB_FRAGMENT_SHADER = `
precision highp float;

uniform vec2 uResolution;
uniform float uBallRadius;
uniform float uEffectSize;
uniform vec3 uGlassPx;
uniform vec3 uBackdrop;
uniform float uTime;
uniform float uWavePhase;
uniform vec4 uSurfaceA;
uniform vec4 uSurfaceB;
uniform vec3 uBands;
uniform float uProgress[6];

varying vec2 vUv;

float sat(float v) { return clamp(v, 0.0, 1.0); }

vec3 spectrumTri(float t) {
  return clamp(vec3(abs(t - 3.0) - 1.0, 2.0 - abs(t - 2.0), 2.0 - abs(t - 4.0)), 0.0, 1.0);
}

vec3 studioHue(float t) {
  vec3 rainbow = spectrumTri(t);
  vec3 studio = vec3(
    rainbow.r * 0.72 + rainbow.b * 0.28 + 0.08,
    rainbow.g * 1.05 + 0.10,
    rainbow.b * 1.18 + rainbow.g * 0.16
  );
  studio = mix(studio, vec3(0.29, 0.91, 0.99), 0.18);
  studio = mix(studio, vec3(1.0, 0.62, 0.86), rainbow.r * 0.22);
  studio = mix(studio, vec3(1.0, 0.84, 0.42), rainbow.g * 0.12);
  return clamp(studio, 0.0, 1.2);
}

vec4 waveLayer(vec2 gid, float press) {
  float res = sat(uSurfaceA.x);
  float layerOpacity = uSurfaceA.y;
  float effectScale = uSurfaceB.x;
  float tw = mod(uWavePhase, 62.831848) * -1.0;
  float lo = sat(uBands.x);
  float md = sat(uBands.y);
  float hi = sat(uBands.z);

  float c52 = 9.2 * 0.01;
  float c55 = 9.4 * 0.01;
  float c58 = 3.15;
  float c61 = (md * 82.0) * 0.0001;
  float c64 = 0.82 * lo + 0.14;
  float c68 = c64;
  float c72 = (md * 5.6 + 0.55) + hi * 5.6;
  float c73 = c72 * res;
  float c76 = lo * 16.0;
  float c75 = md * 11.0 + 4.2;
  float n77 = mix(0.1, c52, res);
  float n78 = mix(0.1, c55, res);
  float n80 = (res * 0.01) * c58;
  float n81 = mix(c75, 1.0, res);
  float omr = 1.0 - res;

  vec2 uv = (gid + 0.5) * 2.0 / vec2(uEffectSize) - 1.0;
  float ws = max(effectScale, 0.01);
  vec2 p = uv / ws;
  float base = mix(0.145, 0.048, res);
  float edge = max(length(p) - base, 0.0);
  float px = p.x;
  float cw = min(abs(px * 0.9), 1.0);
  float cw2 = pow(cos(cw * 1.5707964), 2.0);
  float waveBase = (cw2 * res * c68) * sin(px + tw);
  float atArg2 = px + tw;
  float py = p.y;
  float n80sq = n80 * n80;
  float bft = 0.0001;
  float n139 = (c61 * res) * n78;
  float env68 = cw2 * c68;
  float mouseLift = press * 0.04;

  vec3 colAcc = vec3(0.0);
  vec3 wSum = vec3(0.0);
  for (int i = 0; i < 5; i++) {
    float fi = float(i);
    float t13 = fi * 0.25;
    vec3 hue = mix(vec3(1.0), studioHue(fi * 0.85), vec3(res));
    wSum += hue;
    float ph = atArg2 + mix(-c73, c73, t13);
    float w2 = env68 * sin(ph) + mouseLift;
    float dist = mix(edge, abs(py - w2), res);
    float rad = sqrt(dist * dist + n80sq) + n77;
    float k = dist * 0.018;
    float soft = mix(1.0 / (k * k + 1.0), 1.0, res);
    float glowL = (soft * n78) / rad;
    float band = max(0.0, max(py - max(waveBase, w2), min(waveBase, w2) - py));
    float fill = n139 / (band + bft);
    colAcc += (hue * n81) * (fill + glowL);
  }
  vec3 col = colAcc / max(wSum, vec3(0.0001));

  float tail = omr * (c76 + 4.4);
  float dC = mix(edge, abs(py - waveBase), res);
  float radC = dC + n77;
  float kC = dC * 0.018;
  float softC = mix(1.0 / (kC * kC + 1.0), 1.0, res);
  float cg = (n78 * 0.55 * (softC + tail)) / radC;
  vec3 cgl = pow(vec3(cg) + col, vec3(1.42));

  float fall = exp(-pow(px * 2.01, 2.0));
  col = cgl * mix(1.0, fall, res) * res * sat(layerOpacity);

  float m = max(max(col.r, col.g), col.b);
  col *= (m > 1.0) ? (1.0 / m) : 1.0;
  return vec4(col, sat(m * 1.22));
}

vec2 gCentersA[6];
vec2 gCentersB[6];
vec2 gDirs[6];
float gRadii[6];

float dotsField(vec2 P, vec2 aberOff) {
  float field = 1.0e9;
  for (int j = 0; j < 6; j++) {
    vec2 ofs = aberOff * gDirs[j];
    float lenA = length(P + ofs - gCentersA[j]);
    float lenB = length(P + ofs - gCentersB[j]);
    float dA = lenA - gRadii[j];
    float dB = lenB - gRadii[j];
    float h = max(0.22 - abs(lenA - lenB), 0.0) / 0.22;
    float dPair = min(dA, dB) - h * h * 0.055;
    float h2 = max(0.22 - abs(field - dPair), 0.0) / 0.22;
    field = min(field, dPair) - h2 * h2 * 0.055;
  }
  return field;
}

vec4 dotsLayer(vec2 gid, float press) {
  float appear = sat(uSurfaceA.w) * sat(uSurfaceA.z);
  if (appear <= 0.0001) return vec4(0.0);
  float t = uTime;
  float halfMn = uEffectSize * 0.5;
  float pr = max(uSurfaceB.x, 0.001);
  float drive = mod(t, 62.831848) * 0.36;
  float ringAmp = appear * 0.46;
  float pairAmp = appear * 0.09;

  for (int i = 0; i < 6; i++) {
    float fi = float(i);
    float angle = fi * 1.0471976 + drive;
    float ca = cos(angle);
    float sa = sin(angle);
    vec2 perp = vec2(-sa, ca);
    float fr = fract((fi * 0.167 + t) / 2.0);
    float tri = 1.0 - abs(fr * 2.0 - 1.0);
    float x = sat(tri);
    for (int k = 0; k < 8; k++) {
      float omx = 1.0 - x;
      float a3 = omx * 3.0;
      float c126 = (omx * 0.42) * a3;
      float x2 = x * x;
      float deriv = (x2 * 1.26) + (x * 0.96) * omx + c126;
      if (abs(deriv) < 0.000001) break;
      float num = ((x2 * 0.58) * a3 - tri) + (c126 + x2) * x;
      x = sat(x - num / deriv);
    }
    float ss = x * x * (3.0 - 2.0 * x);
    float amp = mix(0.001, 0.7, ss);
    vec2 dir = vec2(ca, sa);
    vec2 base = (ringAmp * dir) * (1.0 - 2.0 * uProgress[i]);
    float ph2 = pairAmp * amp;
    gCentersA[i] = base - ph2 * perp;
    gCentersB[i] = base + ph2 * perp;
    gDirs[i] = dir;
    gRadii[i] = amp * 0.108;
  }

  vec2 uvPix = (gid + 0.5 - vec2(halfMn)) / halfMn;
  vec2 P = uvPix / pr;
  float aberStep = -0.06 * 0.0909090936;
  vec3 colAcc = vec3(0.0);
  vec3 wSum = vec3(0.0);
  for (int i = 0; i < 12; i++) {
    float ti = float(i) * 0.363636374;
    vec3 hue = studioHue(ti);
    vec2 aberOff = vec2(-(aberStep * float(i)));
    float field = dotsField(P, aberOff);
    float fm = max(field, 0.0);
    float glow = sat(0.05 / pow(fm + 0.0001, 0.66));
    float fadeT = clamp(fm / 0.68, 0.0, 1.0);
    float fade = 1.0 - fadeT * fadeT * (3.0 - 2.0 * fadeT);
    colAcc += hue * (fade * glow);
    wSum += hue;
  }
  float cfield = dotsField(P, vec2(0.0));
  vec3 col = colAcc / max(wSum, vec3(0.0001));
  float cfm = max(cfield, 0.0);
  float cglow = sat(0.05 / pow(cfm + 0.0001, 0.66));
  float cfadeT = clamp(cfm / 0.68, 0.0, 1.0);
  float cfade = 1.0 - cfadeT * cfadeT * (3.0 - 2.0 * cfadeT);
  float mouseBoost = 1.08 + press * 0.38;

  col = (col + (cglow * 0.58) * cfade) * appear * mouseBoost;
  float m = max(max(col.r, col.g), col.b);
  col *= (m > 1.0) ? (1.0 / m) : 1.0;
  return vec4(col, sat(max(max(col.r, col.g), col.b)));
}

vec3 scene(vec2 canvasUv, float press) {
  vec3 bg = uBackdrop;
  vec2 pixelPos = canvasUv * uResolution;
  vec2 effOrigin = 0.5 * (uResolution - vec2(uEffectSize));
  vec2 effectUv = (pixelPos - effOrigin) / uEffectSize;
  vec2 inRect = step(vec2(0.0), effectUv) * step(effectUv, vec2(1.0));
  if (inRect.x * inRect.y < 0.5) return bg;

  vec2 gid = effectUv * uEffectSize;
  vec4 wave = waveLayer(gid, press);
  vec4 dots = dotsLayer(gid, press);
  vec3 effRGB = dots.rgb + wave.rgb * (1.0 - dots.a);
  float effA = dots.a + wave.a * (1.0 - dots.a);

  float gy = sat(effectUv.y);
  float tC = sat(gy - 0.22);
  float vfade = (gy <= 0.22) ? 1.0 : exp(-7.4 * tC * tC);
  float edgeLR = smoothstep(0.0, 0.16, min(effectUv.x, 1.0 - effectUv.x));
  float containerA = sat(uSurfaceB.z) * vfade * edgeLR;

  float outA = effA + containerA * (1.0 - effA);
  return effRGB + bg * (1.0 - outA);
}

float highlightLobe(float dist, float aa, vec2 n, float h, vec2 dir) {
  if (dist < -5.0) return 0.0;
  float t = sat(dist / max(h, 0.001));
  float profile = 1.0 - t;
  float band = sat(dist / aa + 0.5) * sat((h - dist) / aa + 0.5) * profile;
  float angular = sat((dot(dir, n) - 0.48) / 0.52);
  return band * angular;
}

float highlightBand(float d, vec2 n) {
  float dist = -d;
  float aa = max(fwidth(dist), 0.0001);
  vec2 kdir = vec2(0.70710678, 0.70710678);
  vec2 fdir = vec2(-0.70710678, -0.70710678);
  float key = highlightLobe(dist, aa, n, uGlassPx.z, kdir);
  float fill = highlightLobe(dist, aa, n, uGlassPx.z * 1.15, fdir);
  float keyN = key / (1.0 + (1.0 - key) * 8.0);
  float fillN = fill / (1.0 + (1.0 - fill) * 8.0);
  return keyN + fillN * 0.85;
}

void main() {
  vec2 pixel = vec2(gl_FragCoord.x, uResolution.y - gl_FragCoord.y);
  vec2 canvasUv = pixel / uResolution;
  vec2 p = pixel - 0.5 * uResolution;
  float press = uSurfaceB.y;
  float ballR = uBallRadius * (1.0 + press * 0.02);
  float d = length(p) - ballR;
  float alpha = 1.0 - smoothstep(-1.0, 1.0, d);
  if (alpha <= 0.001) discard;

  vec2 grad = normalize(p + vec2(0.00001));
  float t = clamp(-d / max(uGlassPx.x, 0.001), 0.0, 1.0);
  float mag = 1.0 - sqrt(max(1.0 - (1.0 - t) * (1.0 - t), 0.0));
  vec2 rUv = clamp(canvasUv + (uGlassPx.y * mag * grad) / uResolution, vec2(0.0), vec2(1.0));

  vec3 col = scene(rUv, press);
  float fresnel = pow(1.0 - sat(-d / max(ballR, 1.0)), 2.35);
  col += vec3(0.42, 0.95, 1.0) * fresnel * 0.28;
  col += vec3(1.0, 0.58, 0.86) * fresnel * 0.1;
  col += vec3(highlightBand(d, grad) * 0.82);
  col = clamp(col, 0.0, 1.28);

  gl_FragColor = vec4(col * alpha, alpha);
}
`
