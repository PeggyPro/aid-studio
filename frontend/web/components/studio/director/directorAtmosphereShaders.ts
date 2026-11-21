export const skyVertex = /* glsl */ `
  varying vec3 vDirection;
  void main() {
    vDirection = position;
    vec4 clip = projectionMatrix * vec4(mat3(viewMatrix) * position, 1.0);
    gl_Position = clip.xyww;
  }
`

export const skyFragment = /* glsl */ `
  varying vec3 vDirection;
  uniform vec3 uZenith, uHorizon, uFloor, uCloud, uSun, uLightDirection;
  uniform float uCover, uCloudOpacity, uNight, uOutdoor, uFlash, uTime;

  float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
  }
  float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f*f*(3.0 - 2.0*f);
    return mix(mix(hash(i), hash(i+vec2(1,0)), f.x),
               mix(hash(i+vec2(0,1)), hash(i+vec2(1,1)), f.x), f.y);
  }
  float clouds(vec2 p) {
    return .56 * noise(p) + .28 * noise(p * 2.09) + .11 * noise(p * 4.17) + .05 * noise(p * 8.11);
  }

  void main() {
    vec3 direction = normalize(vDirection);
    float height = max(direction.y, 0.0);
    vec3 sky = mix(uHorizon, uZenith, smoothstep(0.0, .76, pow(height, .46)));
    float alignment = max(dot(direction, uLightDirection), 0.0);
    if (uOutdoor > .5 && direction.y > -.02) {
      if (uNight > .5) {
        vec2 starUV = vec2(atan(direction.z, direction.x), asin(direction.y)) * vec2(105.0, 145.0);
        float star = smoothstep(.987, 1.0, hash(floor(starUV)));
        float core = 1.0 - smoothstep(.04, .28, length(fract(starUV) - .5));
        sky += vec3(.51,.65,1.0) * star * core * smoothstep(.035, .4, height);
      }
      float disk = smoothstep(.99945, .99984, alignment);
      float halo = pow(alignment, mix(12.0, 36.0, uNight)) * mix(.17, .095, uNight);
      sky += uSun * (disk * mix(1.65, .78, uNight) + halo);
      if (uCloudOpacity > .01) {
        vec2 p = direction.xz * (2.65 / max(.13, height + .16));
        p += vec2(uTime * .024, uTime * .009);
        float density = clouds(p);
        float edge = 1.0 - uCover;
        float mask = smoothstep(edge - .13, edge + .15, density) * smoothstep(-.02, .22, height);
        float lightSide = clamp(.54 + (density - clouds(p + vec2(.10, .07))) * 4.0, .24, .9);
        vec3 litCloud = uCloud * lightSide + uSun * pow(alignment, 8.0) * (1.0 - uCover) * .16;
        litCloud += vec3(.50,.60,.92) * uFlash * (.4 + pow(alignment, 3.0) * .6);
        sky = mix(sky, litCloud, mask * uCloudOpacity);
      }
    }
    sky = mix(sky, uFloor, smoothstep(0.0, .46, -direction.y));
    gl_FragColor = vec4(sky, 1.0);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }
`

export const precipitationVertex = /* glsl */ `
  attribute float aSeed;
  attribute float aTip;
  uniform float uTime, uSnow, uWind, uFog;
  varying float vOpacity;
  void main() {
    float speed = mix(7.0 + aSeed * 6.5, .7 + aSeed * .85, uSnow);
    float phase = position.y * 17.0 - uTime * speed;
    vec2 wind = vec2(uWind, -.24 * uWind);
    vec2 travel = position.xz * 40.0 + wind * uTime;
    travel += vec2(sin(phase * .55 + aSeed * 30.0), cos(phase * .42)) * uSnow * .65;
    vec2 center = cameraPosition.xz;
    vec2 horizontal = mod(travel - center + 20.0, 40.0) + center - 20.0;
    float fallHeight = mod(phase, 17.0);
    float trail = aTip * (.13 + aSeed * .17);
    vec3 world = vec3(horizontal.x - trail * uWind * .09, fallHeight + trail, horizontal.y + trail * uWind * .02);
    vec4 eye = viewMatrix * vec4(world, 1.0);
    float depth = max(.01, -eye.z);
    vOpacity = smoothstep(.55, 2.0, depth) * (1.0 - smoothstep(15.0, 27.0, depth))
      * smoothstep(.05, .55, fallHeight) * exp(-uFog * depth)
      * mix(.28, 1.0, 1.0 - aTip) * (.55 + aSeed * .45);
    gl_PointSize = clamp((14.0 + 14.0 * aSeed) / depth, 1.2, 8.5);
    gl_Position = projectionMatrix * eye;
  }
`

export const precipitationFragment = /* glsl */ `
  uniform vec3 uColor;
  uniform float uSnow, uOpacity;
  varying float vOpacity;
  void main() {
    float soft = 1.0;
    if (uSnow > .5) soft = 1.0 - smoothstep(.06, .5, length(gl_PointCoord - .5));
    gl_FragColor = vec4(uColor, soft * vOpacity * uOpacity);
    #include <colorspace_fragment>
  }
`

export const gridVertex = /* glsl */ `
  varying vec2 vGrid;
  varying vec3 vWorld;
  void main() {
    vGrid = position.xy;
    vWorld = (modelMatrix * vec4(position, 1.0)).xyz;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`

export const gridFragment = /* glsl */ `
  varying vec2 vGrid;
  varying vec3 vWorld;
  uniform vec3 uLines, uAxes;
  uniform float uCell;
  void main() {
    vec2 coordinates = vGrid / uCell;
    vec2 pixelWidth = max(fwidth(coordinates), vec2(.0001));
    vec2 lineDistance = abs(fract(coordinates - .5) - .5) / pixelWidth;
    float cells = 1.0 - smoothstep(.2, 1.12, min(lineDistance.x, lineDistance.y));
    vec2 axisDistance = abs(coordinates) / pixelWidth;
    float axes = 1.0 - smoothstep(.4, 1.45, min(axisDistance.x, axisDistance.y));
    float fade = 1.0 - smoothstep(22.0, 62.0, distance(cameraPosition, vWorld));
    gl_FragColor = vec4(mix(uLines, uAxes, axes), max(.45 * cells, .68 * axes) * fade);
    #include <colorspace_fragment>
  }
`
