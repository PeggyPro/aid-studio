'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Canvas, useFrame, useThree } from '@react-three/fiber'
import * as THREE from 'three'
import { resolveDirectorPanoramaLoadUrl } from '@/components/studio/director/directorPanorama'
import { fetchMediaBlob, resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import {
  CENTER_PLANE_SIZE,
  CROSS_HIGHLIGHT_ARC_DEG,
  GUIDE_LATITUDE_BANDS,
  LIGHT_RADIUS,
  LONGITUDE_MERIDIANS,
  SNAP_LIGHT_POSES,
  SPHERE_RADIUS,
  azimuthDistance,
  beamSpotIntensity,
  beamStrength,
  guideCrossArcDeg,
  kelvinToColor,
  LIGHTING_BEAM_OPTIC,
  buildLatitudeArcPoints,
  buildLatitudeRingPoints,
  buildMeridianArcPoints,
  buildMeridianPoints,
  easeOutCubic,
  lerpAngle,
  nearestGuideLatitude,
  nearestGuideLongitude,
  positionFromSpherical
} from './lightingSceneMath'
import type { LightingBeamRole, StudioLightingDirection } from './lightingSceneMath'

import {
  StudioLightingPointerControls,
  type LightingDragPreview,
  type LightingPose
} from './StudioLightingPointerControls'

export type LightingViewMode = 'perspective' | 'front'

export interface LightingSceneProps {
  imageUrl: string
  azimuth: number
  elevation: number
  brightness: number
  temperature: number
  rimLight: boolean
  rimAzimuth: number
  rimElevation: number
  viewMode: LightingViewMode
  onLightMove: (azimuth: number, elevation: number, direction: StudioLightingDirection) => void
  onRimMove: (azimuth: number, elevation: number) => void
  onReady?: () => void
  onImageReady?: () => void
}

const SHELL_VERTEX = `
  varying vec3 vNormalW;
  varying vec3 vViewDirW;
  void main() {
    vec4 worldPos = modelMatrix * vec4(position, 1.0);
    vNormalW = normalize(mat3(modelMatrix) * normal);
    vViewDirW = normalize(cameraPosition - worldPos.xyz);
    gl_Position = projectionMatrix * viewMatrix * worldPos;
  }
`

const SHELL_FRAGMENT = `
  uniform vec3 uInner;
  uniform vec3 uEdge;
  uniform vec3 uLightDir;
  uniform vec3 uLightColor;
  uniform float uLit;
  varying vec3 vNormalW;
  varying vec3 vViewDirW;
  void main() {
    vec3 n = normalize(vNormalW);
    vec3 v = normalize(vViewDirW);
    float fresnel = pow(1.0 - max(dot(n, v), 0.0), 1.9);
    float lit = pow(max(dot(n, normalize(uLightDir)), 0.0), 3.8);
    // Frosted grey shell matching reference: clearly visible volume, soft rim (not hard white).
    vec3 base = mix(uInner, uEdge, fresnel * 0.72);
    base += uLightColor * lit * uLit * 0.22;
    float alpha = mix(0.26, 0.58, fresnel) + lit * uLit * 0.08;
    gl_FragColor = vec4(base, clamp(alpha, 0.22, 0.66));
  }
`

/** Gauzy cone: silhouette reads the wedge, fill stays translucent — not a solid torch. */
const CONE_VERTEX = `
  varying float vAlong;
  varying vec3 vNormalW;
  varying vec3 vViewDirW;
  void main() {
    vAlong = uv.y;
    vec4 worldPos = modelMatrix * vec4(position, 1.0);
    vNormalW = normalize(mat3(modelMatrix) * normal);
    vViewDirW = normalize(cameraPosition - worldPos.xyz);
    gl_Position = projectionMatrix * viewMatrix * worldPos;
  }
`

const CONE_FRAGMENT = `
  uniform vec3 uColor;
  uniform float uStrength;
  varying float vAlong;
  varying vec3 vNormalW;
  varying vec3 vViewDirW;
  void main() {
    float sourceFade = smoothstep(0.0, 0.1, vAlong);
    float distanceFade = 1.0 - smoothstep(0.55, 1.0, vAlong);
    float along = sourceFade * distanceFade;
    vec3 n = normalize(vNormalW);
    vec3 v = normalize(vViewDirW);
    float facing = abs(dot(n, v));
    float silhouette = pow(1.0 - facing, 1.35);
    float fill = 0.28 * facing;
    float alpha = (fill + silhouette * 0.9) * along * uStrength;
    gl_FragColor = vec4(uColor, clamp(alpha, 0.0, 0.46));
  }
`

const GLOW_VERTEX = `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`

const GLOW_FRAGMENT = `
  uniform vec3 uColor;
  uniform float uStrength;
  varying vec2 vUv;
  void main() {
    vec2 p = vUv * 2.0 - 1.0;
    float d = length(p);
    float glow = exp(-d * d * 7.0) * (1.0 - smoothstep(0.25, 0.95, d));
    gl_FragColor = vec4(uColor, glow * uStrength);
  }
`

function FrostedShell({
  poseRef,
  lightColor,
  lit
}: {
  poseRef: React.MutableRefObject<{ azimuth: number; elevation: number }>
  lightColor: THREE.Color
  lit: number
}) {
  const uniforms = useMemo(() => ({
    uInner: { value: new THREE.Color('#3a4458') },
    uEdge: { value: new THREE.Color('#a6aebc') },
    uLightDir: { value: new THREE.Vector3(0, 0, 1) },
    uLightColor: { value: lightColor.clone() },
    uLit: { value: lit }
  }), [])

  useFrame(() => {
    const pose = poseRef.current
    const p = positionFromSpherical(pose.azimuth, pose.elevation, 1)
    uniforms.uLightDir.value.set(p.x, p.y, p.z).normalize()
    uniforms.uLightColor.value.copy(lightColor)
    uniforms.uLit.value = lit
  })

  return (
    <mesh renderOrder={4}>
      <sphereGeometry args={[SPHERE_RADIUS, 96, 96]} />
      <shaderMaterial
        transparent
        depthWrite={false}
        side={THREE.FrontSide}
        uniforms={uniforms}
        vertexShader={SHELL_VERTEX}
        fragmentShader={SHELL_FRAGMENT}
      />
    </mesh>
  )
}

/**
 * Role-sized diverging beam. Geometry comes from LIGHTING_BEAM_OPTIC;
 * key and rim share energy so both cones stay readable.
 */
function LightCone({
  role,
  color,
  strength,
  strengthRef,
  renderOrder = 22,
  depthTest = true
}: {
  role: LightingBeamRole
  color: THREE.Color
  strength?: number
  /** Optional per-frame strength (rim appear animation). */
  strengthRef?: React.MutableRefObject<number>
  renderOrder?: number
  depthTest?: boolean
}) {
  const optic = LIGHTING_BEAM_OPTIC[role]
  const length = Math.max(LIGHT_RADIUS * optic.throwScale - 0.08, 0.62)
  const tipRadius = optic.tipRadius
  const baseRadius = optic.baseRadius
  const meshRef = useRef<THREE.Mesh>(null)
  const uniforms = useMemo(() => ({
    uColor: { value: color.clone() },
    uStrength: { value: strength ?? 0 }
  }), [])

  useEffect(() => {
    const mesh = meshRef.current
    if (!mesh) return
    // Beam is visual-only — must not steal pointer hits from the flashlight head.
    mesh.raycast = () => {}
    return () => {
      mesh.raycast = THREE.Mesh.prototype.raycast
    }
  }, [])

  useFrame(() => {
    uniforms.uColor.value.copy(color)
    const next = strengthRef ? strengthRef.current : (strength ?? 0)
    uniforms.uStrength.value = Math.max(0, next)
  })

  return (
    <mesh
      ref={meshRef}
      renderOrder={renderOrder}
      position={[0, 0, length / 2 + 0.03]}
      rotation={[Math.PI / 2, 0, 0]}
    >
      <cylinderGeometry args={[baseRadius, tipRadius, length, 48, 1, true]} />
      <shaderMaterial
        transparent
        depthWrite={false}
        depthTest={depthTest}
        side={THREE.DoubleSide}
        blending={THREE.NormalBlending}
        uniforms={uniforms}
        vertexShader={CONE_VERTEX}
        fragmentShader={CONE_FRAGMENT}
      />
    </mesh>
  )
}

/**
 * More 3D flashlight head (TapNow-like): protruding body + bezel + dark lens face.
 * Local +Z faces the sphere center; body extends outward (−Z).
 */
function FlashlightHead({ size }: { size: number }) {
  return (
    <group>
      {/* Barrel protruding outside the shell */}
      <mesh
        position={[0, 0, -0.055]}
        rotation={[Math.PI / 2, 0, 0]}
        renderOrder={88}
      >
        <cylinderGeometry args={[size * 0.48, size * 0.58, 0.09, 20]} />
        <meshStandardMaterial
          color="#0a0c10"
          metalness={0.72}
          roughness={0.28}
        />
      </mesh>
      {/* Bezel ring */}
      <mesh
        position={[0, 0, -0.01]}
        rotation={[Math.PI / 2, 0, 0]}
        renderOrder={89}
      >
        <cylinderGeometry args={[size * 1.02, size * 0.9, 0.04, 28]} />
        <meshStandardMaterial
          color="#14171e"
          metalness={0.68}
          roughness={0.32}
        />
      </mesh>
      {/* Outer face disc */}
      <mesh position={[0, 0, 0.012]} renderOrder={80}>
        <circleGeometry args={[size, 32]} />
        <meshBasicMaterial
          color="#08090c"
          transparent
          side={THREE.DoubleSide}
          depthTest={false}
          depthWrite={false}
          toneMapped={false}
        />
      </mesh>
      {/* Inner lens recess */}
      <mesh position={[0, 0, 0.03]} renderOrder={81}>
        <circleGeometry args={[size * 0.7, 28]} />
        <meshBasicMaterial
          color="#10131a"
          transparent
          side={THREE.DoubleSide}
          depthTest={false}
          depthWrite={false}
          toneMapped={false}
        />
      </mesh>
      {/* Tiny specular cap for 3D read */}
      <mesh position={[0, 0, 0.038]} renderOrder={82}>
        <sphereGeometry args={[size * 0.22, 16, 12]} />
        <meshStandardMaterial
          color="#0e1016"
          metalness={0.8}
          roughness={0.22}
        />
      </mesh>
    </group>
  )
}

type FlashlightProps = {
  azimuth: number
  elevation: number
  brightness: number
  temperature: number
  poseRef: React.RefObject<LightingPose>
  groupRef: React.RefObject<THREE.Group | null>
  preview: React.RefObject<LightingDragPreview | null>
}

function updateFlashlightPose(
  pose: LightingPose,
  target: LightingPose,
  preview: LightingDragPreview | null,
  role: LightingBeamRole,
  delta: number
) {
  if (preview?.role === role) {
    pose.azimuth = preview.pose.azimuth
    pose.elevation = preview.pose.elevation
    return
  }
  const t = Math.min(1, delta * 4.2)
  pose.azimuth = azimuthDistance(pose.azimuth, target.azimuth) < 0.4
    ? target.azimuth : lerpAngle(pose.azimuth, target.azimuth, t)
  pose.elevation = Math.abs(pose.elevation - target.elevation) < 0.4
    ? target.elevation : pose.elevation + (target.elevation - pose.elevation) * t
}

/** Rear rim flashlight — same energy as the key, slightly tighter cone. */
function RimFlashlightController({
  enabled,
  azimuth,
  elevation,
  brightness,
  temperature,
  poseRef,
  groupRef,
  preview
}: FlashlightProps & { enabled: boolean }) {
  const spotRef = useRef<THREE.SpotLight>(null)
  const appearRef = useRef(0)
  const beamColor = useMemo(() => new THREE.Color(kelvinToColor(temperature)), [temperature])
  const lightTarget = useMemo(() => new THREE.Object3D(), [])
  const inward = useMemo(() => new THREE.Vector3(), [])
  const zAxis = useMemo(() => new THREE.Vector3(0, 0, 1), [])

  useFrame((_, delta) => {
    appearRef.current = enabled ? Math.min(1, appearRef.current + delta / 0.48) : 0
    const appear = easeOutCubic(appearRef.current)
    const pose = poseRef.current
    updateFlashlightPose(pose, { azimuth, elevation }, preview.current, 'rim', delta)

    const group = groupRef.current
    if (!group) return
    group.visible = appear > 0.01
    const p = positionFromSpherical(pose.azimuth, pose.elevation, SPHERE_RADIUS * 1.025)
    group.position.set(p.x, p.y, p.z)
    inward.set(-p.x, -p.y, -p.z).normalize()
    group.quaternion.setFromUnitVectors(zAxis, inward)
    lightTarget.position.copy(group.position).addScaledVector(inward, LIGHT_RADIUS)

    if (spotRef.current) {
      spotRef.current.color.copy(beamColor)
      spotRef.current.intensity = beamSpotIntensity(brightness, 'rim') * appear
      spotRef.current.angle = LIGHTING_BEAM_OPTIC.rim.spotAngle
      spotRef.current.target = lightTarget
      spotRef.current.target.updateMatrixWorld()
    }
  }, -10)

  return (
    <>
      <primitive object={lightTarget} position={[0, 0, 0]} />
      <group ref={groupRef}>
        <spotLight
          ref={spotRef}
          color={beamColor}
          intensity={0}
          distance={16}
          angle={LIGHTING_BEAM_OPTIC.rim.spotAngle}
          penumbra={0.86}
          decay={1.35}
        />
        <RimContactGlow color={beamColor} strength={beamStrength(brightness, 'rim')} size={LIGHTING_BEAM_OPTIC.rim.glowSize} />
        <LightCone role="rim" color={beamColor} strength={beamStrength(brightness, 'rim')} renderOrder={26} />
        <FlashlightHead size={0.105} />
      </group>
    </>
  )
}

const GUIDE_BEAM_SIGMA_DEG = 6.5

function paintGuideVertex(
  tmp: THREE.Vector3,
  lightDir: THREE.Vector3,
  tint: THREE.Color,
  fade: number
) {
  const ang = Math.acos(Math.max(-1, Math.min(1, tmp.dot(lightDir)))) * (180 / Math.PI)
  const brush = Math.exp(-(ang * ang) / (2 * GUIDE_BEAM_SIGMA_DEG * GUIDE_BEAM_SIGMA_DEG))
  if (brush < 0.06) return null
  const litAmt = brush * 0.42 * fade
  return {
    r: Math.min(0.3, litAmt * (0.5 + tint.r * 0.22)),
    g: Math.min(0.32, litAmt * (0.52 + tint.g * 0.2)),
    b: Math.min(0.35, litAmt * (0.55 + tint.b * 0.18))
  }
}

/**
 * Drag field: meridians / parallels / seats under the active flashlight.
 * Visibility is the beam neighborhood — front and back hemispheres use the same rule.
 */
function SphereGuides({
  visible,
  poseRef,
  tintColor
}: {
  visible: boolean
  poseRef: React.MutableRefObject<{ azimuth: number; elevation: number }>
  tintColor: THREE.Color
}) {
  const rootRef = useRef<THREE.Group>(null)
  const gridRef = useRef<THREE.Group>(null)
  const nodesRef = useRef<THREE.Group>(null)
  const opacityRef = useRef(1)
  const lightDir = useMemo(() => new THREE.Vector3(), [])
  const tmp = useMemo(() => new THREE.Vector3(), [])

  const makeVertexLine = (points: Array<[number, number, number]>, base = 0.02) => {
    const positions = new Float32Array(points.length * 3)
    const colors = new Float32Array(points.length * 3)
    for (let i = 0; i < points.length; i++) {
      positions[i * 3] = points[i][0]
      positions[i * 3 + 1] = points[i][1]
      positions[i * 3 + 2] = points[i][2]
      colors[i * 3] = base
      colors[i * 3 + 1] = base
      colors[i * 3 + 2] = base
    }
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
    geo.setAttribute('color', new THREE.BufferAttribute(colors, 3))
    const mat = new THREE.LineBasicMaterial({
      transparent: true,
      depthWrite: false,
      depthTest: false,
      vertexColors: true,
      blending: THREE.AdditiveBlending,
      opacity: 1
    })
    const line = new THREE.Line(geo, mat)
    line.renderOrder = 5
    return line
  }

  useEffect(() => {
    const grid = gridRef.current
    const nodes = nodesRef.current
    if (!grid || !nodes) return

    const created: THREE.Object3D[] = []
    for (const band of GUIDE_LATITUDE_BANDS) {
      const line = makeVertexLine(buildLatitudeRingPoints(band, SPHERE_RADIUS * 1.004, 96), 0)
      line.userData.guideKind = 'lat'
      line.userData.guideValue = band
      grid.add(line)
      created.push(line)
    }
    for (const meridian of LONGITUDE_MERIDIANS) {
      const line = makeVertexLine(buildMeridianPoints(meridian, SPHERE_RADIUS * 1.004, 72), 0)
      line.userData.guideKind = 'lon'
      line.userData.guideValue = meridian
      grid.add(line)
      created.push(line)
    }

    const nodeGeo = new THREE.SphereGeometry(0.016, 10, 10)
    const nodeMat = new THREE.MeshBasicMaterial({
      color: '#d8dee8',
      transparent: true,
      opacity: 0,
      depthWrite: false,
      depthTest: false,
      blending: THREE.AdditiveBlending
    })
    for (const pose of SNAP_LIGHT_POSES) {
      const az = pose.azimuth === -180 ? 180 : pose.azimuth
      const p = positionFromSpherical(az, pose.elevation, SPHERE_RADIUS * 1.006)
      const mesh = new THREE.Mesh(nodeGeo, nodeMat.clone())
      mesh.position.set(p.x, p.y, p.z)
      mesh.renderOrder = 6
      mesh.userData.seat = { azimuth: az, elevation: pose.elevation }
      nodes.add(mesh)
      created.push(mesh)
    }

    return () => {
      for (const obj of created) {
        grid.remove(obj)
        nodes.remove(obj)
        const mesh = obj as THREE.Mesh
        mesh.geometry?.dispose?.()
        const mat = mesh.material as THREE.Material | THREE.Material[] | undefined
        if (Array.isArray(mat)) mat.forEach((m) => m.dispose())
        else mat?.dispose?.()
      }
      nodeGeo.dispose()
      nodeMat.dispose()
    }
  }, [])

  useFrame((_, delta) => {
    const root = rootRef.current
    if (!root) return
    const target = visible ? 1 : 0
    opacityRef.current += (target - opacityRef.current) * Math.min(1, delta * 7)
    root.visible = opacityRef.current > 0.02
    const fade = opacityRef.current
    if (fade <= 0.02) return

    const pose = poseRef.current
    const p = positionFromSpherical(pose.azimuth, pose.elevation, 1)
    lightDir.set(p.x, p.y, p.z).normalize()

    if (gridRef.current) {
      gridRef.current.traverse((obj) => {
        const line = obj as THREE.Line
        if (!line.isLine) return
        const pos = line.geometry.getAttribute('position') as THREE.BufferAttribute
        const col = line.geometry.getAttribute('color') as THREE.BufferAttribute
        if (!pos || !col) return
        for (let i = 0; i < pos.count; i++) {
          tmp.fromBufferAttribute(pos, i).normalize()
          const lit = paintGuideVertex(tmp, lightDir, tintColor, fade)
          if (!lit) col.setXYZ(i, 0, 0, 0)
          else col.setXYZ(i, lit.r, lit.g, lit.b)
        }
        col.needsUpdate = true
      })
    }

    if (nodesRef.current) {
      let best: THREE.Mesh | null = null
      let bestScore = Number.POSITIVE_INFINITY
      for (const child of nodesRef.current.children) {
        const mesh = child as THREE.Mesh
        const seat = mesh.userData.seat as { azimuth: number; elevation: number } | undefined
        if (!seat) continue
        tmp.copy(mesh.position).normalize()
        const lit = paintGuideVertex(tmp, lightDir, tintColor, fade)
        const score = azimuthDistance(pose.azimuth, seat.azimuth) + Math.abs(pose.elevation - seat.elevation)
        if (score < bestScore) {
          bestScore = score
          best = mesh
        }
        const mat = mesh.material as THREE.MeshBasicMaterial
        mat.opacity = lit ? 0.06 * fade : 0
        mat.color.set('#a8b0bc')
      }
      if (best) {
        const mat = best.material as THREE.MeshBasicMaterial
        mat.opacity = 0.2 * fade
        mat.color.setRGB(0.62 + tintColor.r * 0.15, 0.64 + tintColor.g * 0.14, 0.68 + tintColor.b * 0.12)
      }
    }
  })

  return (
    <group ref={rootRef}>
      <group ref={gridRef} />
      <group ref={nodesRef} />
    </group>
  )
}

/** Resting gizmo: a short glowing cross on the flashlight's guide seat. */
function SeatedCrossGlow({
  visible,
  poseRef,
  role,
  tintColor
}: {
  visible: boolean
  poseRef: React.MutableRefObject<{ azimuth: number; elevation: number }>
  role: LightingBeamRole
  tintColor: THREE.Color
}) {
  const rootRef = useRef<THREE.Group>(null)
  const crossLatRef = useRef<THREE.Line | null>(null)
  const crossLonRef = useRef<THREE.Line | null>(null)
  const seatNodeRef = useRef<THREE.Mesh>(null)
  const opacityRef = useRef(1)
  const tmp = useMemo(() => new THREE.Vector3(), [])
  const segments = 40
  const halfArc = guideCrossArcDeg(role)

  useEffect(() => {
    const root = rootRef.current
    if (!root) return
    const makeSoftArc = () => {
      const pts = buildLatitudeArcPoints(0, 0, CROSS_HIGHLIGHT_ARC_DEG, SPHERE_RADIUS * 1.01, segments)
      const positions = new Float32Array(pts.length * 3)
      const colors = new Float32Array(pts.length * 3)
      const geo = new THREE.BufferGeometry()
      geo.setAttribute('position', new THREE.BufferAttribute(positions, 3))
      geo.setAttribute('color', new THREE.BufferAttribute(colors, 3))
      const mat = new THREE.LineBasicMaterial({
        transparent: true,
        depthWrite: false,
        depthTest: false,
        vertexColors: true,
        blending: THREE.AdditiveBlending,
        opacity: 1
      })
      const line = new THREE.Line(geo, mat)
      line.renderOrder = 20
      return line
    }
    const crossLat = makeSoftArc()
    const crossLon = makeSoftArc()
    crossLatRef.current = crossLat
    crossLonRef.current = crossLon
    root.add(crossLat)
    root.add(crossLon)
    return () => {
      root.remove(crossLat)
      root.remove(crossLon)
      crossLat.geometry.dispose()
      ;(crossLat.material as THREE.Material).dispose()
      crossLon.geometry.dispose()
      ;(crossLon.material as THREE.Material).dispose()
      crossLatRef.current = null
      crossLonRef.current = null
    }
  }, [])

  useFrame((_, delta) => {
    const root = rootRef.current
    if (!root) return
    const target = visible ? 1 : 0
    opacityRef.current += (target - opacityRef.current) * Math.min(1, delta * 7)
    root.visible = opacityRef.current > 0.02
    const fade = opacityRef.current
    if (fade <= 0.02) return

    const pose = poseRef.current
    const guideLat = nearestGuideLatitude(pose.elevation)
    const guideLon = nearestGuideLongitude(pose.azimuth)
    const latPts = buildLatitudeArcPoints(guideLat, pose.azimuth, halfArc, SPHERE_RADIUS * 1.008, segments)
    const lonPts = buildMeridianArcPoints(guideLon, pose.elevation, halfArc, SPHERE_RADIUS * 1.008, segments)
    const tr = tintColor.r
    const tg = tintColor.g
    const tb = tintColor.b

    const patchCross = (line: THREE.Line | null, pts: Array<[number, number, number]>) => {
      if (!line) return
      line.visible = true
      const pos = line.geometry.getAttribute('position') as THREE.BufferAttribute
      const col = line.geometry.getAttribute('color') as THREE.BufferAttribute
      if (!pos || !col || pos.count !== pts.length) return
      for (let i = 0; i < pts.length; i++) {
        const t = (i / (pts.length - 1)) * 2 - 1
        const dist = Math.abs(t)
        const arm = Math.pow(1 - Math.min(1, dist), 2.15)
        const hole = dist < 0.16 ? dist / 0.16 : 1
        tmp.set(pts[i][0], pts[i][1], pts[i][2]).normalize()
        const falloff = arm * hole * fade * 0.68 * 1.55
        pos.setXYZ(i, pts[i][0], pts[i][1], pts[i][2])
        col.setXYZ(
          i,
          falloff * (0.58 + tr * 0.25),
          falloff * (0.6 + tg * 0.24),
          falloff * (0.64 + tb * 0.22)
        )
      }
      pos.needsUpdate = true
      col.needsUpdate = true
      line.geometry.computeBoundingSphere()
    }
    patchCross(crossLatRef.current, latPts)
    patchCross(crossLonRef.current, lonPts)

    if (seatNodeRef.current) {
      const seat = positionFromSpherical(pose.azimuth, pose.elevation, SPHERE_RADIUS * 1.008)
      seatNodeRef.current.position.set(seat.x, seat.y, seat.z)
      const mat = seatNodeRef.current.material as THREE.MeshBasicMaterial
      mat.opacity = 0.58 * fade
      mat.color.setRGB(0.72 + tr * 0.12, 0.74 + tg * 0.1, 0.78 + tb * 0.08)
    }
  })

  return (
    <group ref={rootRef}>
      <mesh ref={seatNodeRef} renderOrder={21} visible>
        <sphereGeometry args={[role === 'rim' ? 0.028 : 0.038, 12, 12]} />
        <meshBasicMaterial
          color="#d8e2f4"
          transparent
          opacity={0.55}
          depthWrite={false}
          depthTest={false}
          blending={THREE.AdditiveBlending}
        />
      </mesh>
    </group>
  )
}

/** Tiny soft bloom where beam meets the sphere — kept very subtle. */
function RimContactGlow({
  color,
  strength,
  strengthRef,
  depthTest = true,
  size = LIGHTING_BEAM_OPTIC.key.glowSize
}: {
  color: THREE.Color
  strength?: number
  strengthRef?: React.MutableRefObject<number>
  depthTest?: boolean
  size?: number
}) {
  const glowRef = useRef<THREE.Mesh>(null)
  const uniforms = useMemo(() => ({
    uColor: { value: color.clone() },
    uStrength: { value: 0.12 }
  }), [])

  useEffect(() => {
    const mesh = glowRef.current
    if (!mesh) return
    mesh.raycast = () => {}
    return () => {
      mesh.raycast = THREE.Mesh.prototype.raycast
    }
  }, [])

  useFrame(() => {
    uniforms.uColor.value.copy(color)
    const s = strengthRef ? strengthRef.current : (strength ?? 0)
    uniforms.uStrength.value = 0.04 + s * 0.06
  })

  const rimZ = Math.max(LIGHT_RADIUS - SPHERE_RADIUS, 0.015)

  return (
    <mesh ref={glowRef} renderOrder={9} position={[0, 0, rimZ]}>
      <planeGeometry args={[size, size]} />
      <shaderMaterial
        transparent
        depthWrite={false}
        depthTest={depthTest}
        side={THREE.DoubleSide}
        blending={THREE.AdditiveBlending}
        uniforms={uniforms}
        vertexShader={GLOW_VERTEX}
        fragmentShader={GLOW_FRAGMENT}
      />
    </mesh>
  )
}

function SceneSubject({
  viewMode,
  imageUrl,
  brightness,
  onImageReady
}: {
  viewMode: LightingViewMode
  imageUrl: string
  brightness: number
  onImageReady?: () => void
}) {
  // Slight card tilt in perspective so front key + back rim are readable (TapNow-like).
  const rotY = viewMode === 'perspective' ? -0.38 : 0
  const rotX = viewMode === 'perspective' ? 0.07 : 0

  return (
    <group rotation={[rotX, rotY, 0]}>
      {imageUrl ? <CenterImage url={imageUrl} brightness={brightness} onReady={onImageReady} /> : (
        <mesh>
          <planeGeometry args={[CENTER_PLANE_SIZE, CENTER_PLANE_SIZE]} />
          <meshStandardMaterial color="#141820" roughness={0.9} metalness={0} />
        </mesh>
      )}
    </group>
  )
}

function CenterImage({ url, brightness, onReady }: { url: string; brightness: number; onReady?: () => void }) {
  const [texture, setTexture] = useState<THREE.Texture | null>(null)
  const [failed, setFailed] = useState(false)
  const [aspect, setAspect] = useState(1)
  const emissiveBoost = 0.12 + (brightness / 100) * 0.7
  const readyRef = useRef(false)

  const notifyReady = useCallback(() => {
    if (readyRef.current) return
    readyRef.current = true
    onReady?.()
  }, [onReady])

  useEffect(() => {
    readyRef.current = false
    if (!url) {
      setTexture(null)
      setFailed(true)
      notifyReady()
      return
    }
    let disposed = false
    let current: THREE.Texture | null = null
    let objectUrl: string | null = null
    setTexture(null)
    setFailed(false)
    setAspect(1)

    const loader = new THREE.TextureLoader()
    loader.setCrossOrigin('anonymous')

    const apply = (loaded: THREE.Texture) => {
      if (disposed) {
        loaded.dispose()
        return
      }
      loaded.colorSpace = THREE.SRGBColorSpace
      loaded.minFilter = THREE.LinearFilter
      loaded.magFilter = THREE.LinearFilter
      loaded.anisotropy = 4
      loaded.needsUpdate = true
      current = loaded
      const image = loaded.image as HTMLImageElement | ImageBitmap | { width?: number; height?: number }
      const width = 'naturalWidth' in image ? image.naturalWidth : image.width
      const height = 'naturalHeight' in image ? image.naturalHeight : image.height
      if (width && height) setAspect(width / height)
      setTexture(loaded)
      setFailed(false)
      notifyReady()
    }

    const loadFromHref = (href: string) =>
      new Promise<THREE.Texture>((resolve, reject) => {
        loader.load(href, resolve, undefined, reject)
      })

    const run = async () => {
      const candidates = Array.from(new Set([
        resolveMediaPlaybackUrl(url),
        url,
        resolveDirectorPanoramaLoadUrl(url)
      ].filter(Boolean)))

      for (const href of candidates) {
        if (disposed) return
        try {
          apply(await loadFromHref(href))
          return
        } catch {
          /* try blob / next candidate */
        }
      }

      try {
        const blob = await fetchMediaBlob(url)
        if (disposed) return
        if (blob) {
          objectUrl = URL.createObjectURL(blob)
          apply(await loadFromHref(objectUrl))
          return
        }
      } catch {
        /* fall through */
      }

      if (!disposed) {
        setFailed(true)
        notifyReady()
      }
    }

    void run()

    return () => {
      disposed = true
      current?.dispose()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [notifyReady, url])

  const max = CENTER_PLANE_SIZE
  const width = texture ? (aspect >= 1 ? max : max * aspect) : max
  const height = texture ? (aspect >= 1 ? max / aspect : max) : max

  if (!texture) {
    return (
      <mesh>
        <planeGeometry args={[max, max]} />
        <meshStandardMaterial color="#141820" roughness={0.9} metalness={0} />
      </mesh>
    )
  }

  return (
    <group renderOrder={12}>
      <mesh position={[0, 0, 0]} renderOrder={14}>
        <planeGeometry args={[width, height]} />
        <meshStandardMaterial
          map={texture}
          emissiveMap={texture}
          emissive="#ffffff"
          emissiveIntensity={emissiveBoost}
          roughness={0.88}
          metalness={0}
          toneMapped={false}
          side={THREE.DoubleSide}
          depthWrite
          transparent={false}
        />
      </mesh>
    </group>
  )
}

function FlashlightController({
  azimuth,
  elevation,
  brightness,
  temperature,
  poseRef,
  groupRef,
  preview
}: FlashlightProps) {
  const spotRef = useRef<THREE.SpotLight>(null)
  const beamColor = useMemo(() => new THREE.Color(kelvinToColor(temperature)), [temperature])
  const strength = beamStrength(brightness, 'key')
  const lightTarget = useMemo(() => new THREE.Object3D(), [])
  const inward = useMemo(() => new THREE.Vector3(), [])
  const zAxis = useMemo(() => new THREE.Vector3(0, 0, 1), [])

  useFrame((_, delta) => {
    const pose = poseRef.current
    updateFlashlightPose(pose, { azimuth, elevation }, preview.current, 'key', delta)
    const group = groupRef.current
    if (!group) return
    // Sit outside guide nodes / seat glow so the black handle always covers the cross center.
    const p = positionFromSpherical(pose.azimuth, pose.elevation, SPHERE_RADIUS * 1.025)
    group.position.set(p.x, p.y, p.z)
    inward.set(-p.x, -p.y, -p.z).normalize()
    group.quaternion.setFromUnitVectors(zAxis, inward)
    if (spotRef.current) {
      spotRef.current.color.copy(beamColor)
      spotRef.current.intensity = beamSpotIntensity(brightness, 'key')
      spotRef.current.angle = LIGHTING_BEAM_OPTIC.key.spotAngle
      spotRef.current.target = lightTarget
      spotRef.current.target.updateMatrixWorld()
    }
  })

  return (
    <>
      <primitive object={lightTarget} position={[0, 0, 0]} />
      <group ref={groupRef}>
        <spotLight
          ref={spotRef}
          color={beamColor}
          intensity={beamSpotIntensity(brightness, 'key')}
          distance={16}
          angle={LIGHTING_BEAM_OPTIC.key.spotAngle}
          penumbra={0.86}
          decay={1.25}
        />
        <RimContactGlow color={beamColor} strength={strength} size={LIGHTING_BEAM_OPTIC.key.glowSize} />
        <LightCone role="key" color={beamColor} strength={strength} />
        <FlashlightHead size={0.115} />
      </group>
    </>
  )
}

function IntroRig({ children, rigRef: ref }: { children: React.ReactNode; rigRef: React.RefObject<THREE.Group | null> }) {
  const progress = useRef(0)
  const started = useRef(false)

  useFrame((_, delta) => {
    if (!ref.current) return
    if (!started.current) {
      started.current = true
      ref.current.rotation.y = 0.95
      ref.current.rotation.x = 0.18
    }
    if (progress.current >= 1) return
    progress.current = Math.min(1, progress.current + delta * 0.85)
    const t = easeOutCubic(progress.current)
    ref.current.rotation.y = 0.95 * (1 - t)
    ref.current.rotation.x = 0.18 * (1 - t)
  })

  return <group ref={ref}>{children}</group>
}

function LightingStage(props: LightingSceneProps) {
  const {
    imageUrl,
    azimuth,
    elevation,
    brightness,
    temperature,
    rimLight,
    rimAzimuth,
    rimElevation,
    viewMode,
    onLightMove,
    onRimMove,
    onReady
  } = props

  const [draggingRole, setDraggingRole] = useState<LightingBeamRole | null>(null)
  const [imageReady, setImageReady] = useState(!imageUrl)
  const poseRef = useRef({ azimuth, elevation })
  const rimPoseRef = useRef({ azimuth: rimAzimuth, elevation: rimElevation })
  const rigRef = useRef<THREE.Group>(null)
  const keyHead = useRef<THREE.Group>(null)
  const rimHead = useRef<THREE.Group>(null)
  const preview = useRef<LightingDragPreview | null>(null)
  const beamColor = useMemo(() => new THREE.Color(kelvinToColor(temperature)), [temperature])
  const lit = 0.28 + (brightness / 100) * 0.55

  useEffect(() => {
    setImageReady(!imageUrl)
  }, [imageUrl])

  const handleImageReady = useCallback(() => {
    setImageReady(true)
  }, [])

  return (
    <>
      <color attach="background" args={['#222222']} />
      <ambientLight intensity={0.62} color="#3a4558" />
      <hemisphereLight args={['#5a6780', '#0c0e14', 0.52]} />
      <directionalLight position={[2.2, 3, 4]} intensity={0.32} color="#d8deea" />
      <IntroRig rigRef={rigRef}>
        <FrostedShell poseRef={poseRef} lightColor={beamColor} lit={lit} />
        <SphereGuides
          visible={draggingRole !== null}
          poseRef={draggingRole === 'rim' ? rimPoseRef : poseRef}
          tintColor={beamColor}
        />
        <SeatedCrossGlow
          visible={draggingRole === null}
          poseRef={poseRef}
          role="key"
          tintColor={beamColor}
        />
        <SeatedCrossGlow
          visible={Boolean(rimLight) && draggingRole === null}
          poseRef={rimPoseRef}
          role="rim"
          tintColor={beamColor}
        />
        <SceneSubject
          viewMode={viewMode}
          imageUrl={imageUrl}
          brightness={brightness}
          onImageReady={handleImageReady}
        />
        <FlashlightController
          azimuth={azimuth}
          elevation={elevation}
          brightness={brightness}
          temperature={temperature}
          poseRef={poseRef}
          groupRef={keyHead}
          preview={preview}
        />
        <RimFlashlightController
          enabled={rimLight}
          azimuth={rimAzimuth}
          elevation={rimElevation}
          brightness={brightness}
          temperature={temperature}
          poseRef={rimPoseRef}
          groupRef={rimHead}
          preview={preview}
        />
      </IntroRig>
      <StudioLightingPointerControls
        rigRef={rigRef}
        keyHead={keyHead}
        rimHead={rimHead}
        keyPose={poseRef}
        rimPose={rimPoseRef}
        rimEnabled={rimLight}
        preview={preview}
        onDraggingChange={setDraggingRole}
        onLightMove={onLightMove}
        onRimMove={onRimMove}
      />
      <SceneCamera viewMode={viewMode} />
      <SceneReadyGate imageUrl={imageUrl} imageReady={imageReady} onReady={onReady} />
    </>
  )
}

const VIEW_CAMERA = {
  // 透视：偏右前方，左侧主光 + 背后轮廓光同时可见。
  perspective: { position: new THREE.Vector3(3.05, 0.92, 5.75), fov: 34 },
  front: { position: new THREE.Vector3(0, 0, 7.55), fov: 36 }
} as const

/** Orbit / ease the camera when switching 透视 ↔ 正面 (no hard cut). */
function SceneCamera({ viewMode }: { viewMode: LightingViewMode }) {
  const { camera } = useThree()
  const fromPos = useRef(new THREE.Vector3())
  const toPos = useRef(VIEW_CAMERA.perspective.position.clone())
  const fromFov = useRef<number>(VIEW_CAMERA.perspective.fov)
  const toFov = useRef<number>(VIEW_CAMERA.perspective.fov)
  const progress = useRef(1)
  const lookAt = useMemo(() => new THREE.Vector3(0, 0, 0), [])
  const scratch = useMemo(() => new THREE.Vector3(), [])

  useEffect(() => {
    if (!(camera instanceof THREE.PerspectiveCamera)) return
    fromPos.current.copy(camera.position)
    fromFov.current = camera.fov
    const preset = VIEW_CAMERA[viewMode]
    toPos.current.copy(preset.position)
    toFov.current = preset.fov
    progress.current = 0
  }, [camera, viewMode])

  useFrame((_, delta) => {
    if (!(camera instanceof THREE.PerspectiveCamera)) return
    if (progress.current >= 1) return
    progress.current = Math.min(1, progress.current + delta / 0.58)
    const t = easeOutCubic(progress.current)
    scratch.lerpVectors(fromPos.current, toPos.current, t)
    camera.position.copy(scratch)
    camera.fov = fromFov.current + (toFov.current - fromFov.current) * t
    camera.lookAt(lookAt)
    camera.updateProjectionMatrix()
  })

  return null
}

function SceneReadyGate({
  imageUrl,
  imageReady,
  onReady
}: {
  imageUrl: string
  imageReady: boolean
  onReady?: () => void
}) {
  const frameReady = useRef(false)
  const notified = useRef(false)

  const tryNotify = useCallback(() => {
    if (notified.current) return
    const needsImage = Boolean(imageUrl)
    if (frameReady.current && (!needsImage || imageReady)) {
      notified.current = true
      onReady?.()
    }
  }, [imageReady, imageUrl, onReady])

  useEffect(() => {
    notified.current = false
  }, [imageUrl])

  useFrame(() => {
    frameReady.current = true
    tryNotify()
  })

  useEffect(() => {
    tryNotify()
  }, [tryNotify])

  return null
}

export function StudioLightingR3FScene(props: LightingSceneProps) {
  return (
    <Canvas
      className="light-scene-3d-canvas"
      dpr={[1, 2]}
      camera={{ position: [3.05, 0.92, 5.75], fov: 34, near: 0.1, far: 100 }}
      gl={{
        antialias: true,
        alpha: true,
        toneMapping: THREE.ACESFilmicToneMapping,
        toneMappingExposure: 1.1
      }}
      onCreated={({ gl }) => {
        gl.domElement.dataset.engine = `three.js r${THREE.REVISION}`
        gl.domElement.style.touchAction = 'none'
      }}
    >
      <LightingStage {...props} />
    </Canvas>
  )
}
