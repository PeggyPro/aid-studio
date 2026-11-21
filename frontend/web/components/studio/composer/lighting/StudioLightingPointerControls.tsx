'use client'

import { useEffect, useEffectEvent, type RefObject } from 'react'
import { useThree } from '@react-three/fiber'
import * as THREE from 'three'
import {
  LIGHT_RADIUS,
  azimuthDistance,
  positionFromSpherical,
  snapFlashlightToGuide,
  snapRimFlashlightToGuide,
  sphericalFromPosition,
  type LightingBeamRole,
  type StudioLightingDirection
} from './lightingSceneMath'

export type LightingPose = { azimuth: number; elevation: number }
export type LightingDragPreview = { role: LightingBeamRole; pose: LightingPose }

const CENTER = new THREE.Vector3()
const HANDLE_RADIUS_PX = 15
const DRAG_THRESHOLD_PX = 3

/** Keep the chosen surface and constrain its edge without reflecting to the opposite side. */
function poseOnSurface(ray: THREE.Ray, farSide: boolean, role: LightingBeamRole, hit: THREE.Vector3): LightingPose {
  const middle = -ray.origin.dot(ray.direction)
  const radiusSq = LIGHT_RADIUS ** 2
  const halfChordSq = radiusSq - ray.at(middle, hit).lengthSq()
  if (halfChordSq >= 0) {
    const halfChord = Math.sqrt(halfChordSq)
    ray.at(Math.max(0, middle + (farSide ? halfChord : -halfChord)), hit)
  } else {
    // Pin the projected head to the silhouette once the pointer leaves the sphere.
    const eyeDistanceSq = ray.origin.lengthSq()
    const tangentRatio = radiusSq / eyeDistanceSq
    hit.copy(ray.direction)
      .addScaledVector(ray.origin, middle / eyeDistanceSq)
      .setLength(LIGHT_RADIUS * Math.sqrt(1 - tangentRatio))
      .addScaledVector(ray.origin, tangentRatio)
  }

  const hemisphere = role === 'rim' ? -1 : 1
  if (hit.z * hemisphere < 0) {
    const planeDistance = -ray.origin.z / ray.direction.z
    if (Number.isFinite(planeDistance) && planeDistance > 0) ray.at(planeDistance, hit)
    hit.z = Math.max(0.0001, Math.hypot(hit.x, hit.y) * 0.0001) * hemisphere
  }

  const pose = sphericalFromPosition(hit.x, hit.y, hit.z)
  return { azimuth: pose.azimuth, elevation: Math.max(-78, Math.min(78, pose.elevation)) }
}

export function StudioLightingPointerControls({
  rigRef,
  keyHead,
  rimHead,
  keyPose,
  rimPose,
  rimEnabled,
  preview,
  onDraggingChange,
  onLightMove,
  onRimMove
}: {
  rigRef: RefObject<THREE.Group | null>
  keyHead: RefObject<THREE.Group | null>
  rimHead: RefObject<THREE.Group | null>
  keyPose: RefObject<LightingPose>
  rimPose: RefObject<LightingPose>
  rimEnabled: boolean
  preview: RefObject<LightingDragPreview | null>
  onDraggingChange: (role: LightingBeamRole | null) => void
  onLightMove: (azimuth: number, elevation: number, direction: StudioLightingDirection) => void
  onRimMove: (azimuth: number, elevation: number) => void
}) {
  const { gl, camera } = useThree()
  const commit = useEffectEvent(({ role, pose }: LightingDragPreview) => {
    if (role === 'key') {
      const snapped = snapFlashlightToGuide(pose.azimuth, pose.elevation)
      onLightMove(snapped.azimuth, snapped.elevation, snapped.direction)
      return
    }
    const seat = snapRimFlashlightToGuide(pose.azimuth, pose.elevation)
    const nearSeat = azimuthDistance(seat.azimuth, pose.azimuth) <= 8
      && Math.abs(seat.elevation - pose.elevation) <= 8
    const result = nearSeat ? seat : pose
    onRimMove(result.azimuth, result.elevation)
  })

  useEffect(() => {
    const canvas = gl.domElement
    const raycaster = new THREE.Raycaster()
    const inverseRig = new THREE.Matrix4()
    const ndc = new THREE.Vector2()
    const point = new THREE.Vector3()
    const hit = new THREE.Vector3()
    let drag: {
      pointerId: number
      role: LightingBeamRole
      farSide: boolean
      startX: number
      startY: number
      offsetX: number
      offsetY: number
      moved: boolean
    } | null = null

    function localRay(x: number, y: number, rect: DOMRect) {
      const rig = rigRef.current
      if (!rig || !rect.width || !rect.height) return null
      camera.updateMatrixWorld()
      rig.updateWorldMatrix(true, false)
      ndc.set((x - rect.left) * 2 / rect.width - 1, 1 - (y - rect.top) * 2 / rect.height)
      raycaster.setFromCamera(ndc, camera)
      return raycaster.ray.applyMatrix4(inverseRig.copy(rig.matrixWorld).invert())
    }

    function findHandle(x: number, y: number, rect: DOMRect) {
      let closest: { role: LightingBeamRole; x: number; y: number } | null = null
      let minDistanceSq = HANDLE_RADIUS_PX ** 2
      // A coincident handle stays selectable as rim when it is enabled.
      for (const role of ['key', 'rim'] as const) {
        const head = (role === 'rim' ? rimHead : keyHead).current
        if (!head?.visible || (role === 'rim' && !rimEnabled)) continue
        head.getWorldPosition(point).project(camera)
        if (point.z < -1 || point.z > 1) continue
        const px = rect.left + (point.x + 1) * rect.width / 2
        const py = rect.top + (1 - point.y) * rect.height / 2
        const distanceSq = (x - px) ** 2 + (y - py) ** 2
        if (distanceSq > minDistanceSq) continue
        closest = { role, x: px, y: py }
        minDistanceSq = distanceSq
      }
      return closest
    }

    function updateDrag(event: PointerEvent) {
      if (!drag || drag.pointerId !== event.pointerId) return
      if (!drag.moved && Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < DRAG_THRESHOLD_PX) return
      const ray = localRay(event.clientX - drag.offsetX, event.clientY - drag.offsetY, canvas.getBoundingClientRect())
      if (!ray) return
      drag.moved = true
      preview.current = { role: drag.role, pose: poseOnSurface(ray, drag.farSide, drag.role, hit) }
    }

    function start(event: PointerEvent) {
      if (drag || event.button !== 0 || !event.isPrimary) return
      const rect = canvas.getBoundingClientRect()
      camera.updateMatrixWorld()
      const handle = findHandle(event.clientX, event.clientY, rect)
      const ray = localRay(handle?.x ?? event.clientX, handle?.y ?? event.clientY, rect)
      if (!ray || (!handle && ray.distanceSqToPoint(CENTER) > LIGHT_RADIUS ** 2)) return

      const role = handle?.role ?? 'key'
      const pose = (role === 'rim' ? rimPose : keyPose).current
      const position = positionFromSpherical(pose.azimuth, pose.elevation)
      const farSide = Boolean(handle && ray.direction.dot(point.set(position.x, position.y, position.z)) > 0)
      drag = {
        pointerId: event.pointerId, role, farSide,
        startX: event.clientX, startY: event.clientY,
        offsetX: event.clientX - (handle?.x ?? event.clientX),
        offsetY: event.clientY - (handle?.y ?? event.clientY),
        moved: !handle
      }
      preview.current = { role, pose: handle ? { ...pose } : poseOnSurface(ray, farSide, role, hit) }
      try {
        canvas.setPointerCapture(event.pointerId)
      } catch {
        reset()
        return
      }
      event.preventDefault()
      event.stopPropagation()
      canvas.style.cursor = 'grabbing'
      onDraggingChange(role)
    }

    function reset() {
      const pointerId = drag?.pointerId
      drag = null
      preview.current = null
      canvas.style.cursor = ''
      if (pointerId === undefined) return
      if (canvas.hasPointerCapture(pointerId)) canvas.releasePointerCapture(pointerId)
      onDraggingChange(null)
    }

    function end(event: PointerEvent) {
      if (drag?.pointerId !== event.pointerId) return
      if (event.type === 'pointerup') {
        updateDrag(event)
        const result = preview.current
        if (drag.moved && result) commit(result)
      }
      reset()
    }

    canvas.addEventListener('pointerdown', start)
    canvas.addEventListener('pointermove', updateDrag)
    canvas.addEventListener('pointerup', end)
    canvas.addEventListener('pointercancel', end)
    canvas.addEventListener('lostpointercapture', end)
    return () => {
      canvas.removeEventListener('pointerdown', start)
      canvas.removeEventListener('pointermove', updateDrag)
      canvas.removeEventListener('pointerup', end)
      canvas.removeEventListener('pointercancel', end)
      canvas.removeEventListener('lostpointercapture', end)
      reset()
    }
  }, [camera, gl, keyHead, keyPose, onDraggingChange, preview, rigRef, rimEnabled, rimHead, rimPose])

  return null
}
