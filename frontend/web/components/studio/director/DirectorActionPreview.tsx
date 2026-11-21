'use client'
import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import type { DirectorActorActionClip } from '@/types/studio'
import { createDirectorActor } from '@/utils/studio/directorScene'
import { sampleDirectorActorAction } from '@/utils/studio/directorActorActions'
import {
  DirectorMannequinRuntimeCache,
  applyDirectorMannequinPose,
  findDirectorMannequinModelRoot
} from './directorMannequinRuntime'
import { disposeDirectorObjectTree } from './directorRuntimeResources'

export function DirectorActionPreview({ clip }: { clip: DirectorActorActionClip }) {
  const dirty = useRef(true), restart = useRef(true)
  const host = useRef<HTMLDivElement>(null), live = useRef(clip), [error, setError] = useState('')
  useEffect(() => { live.current = clip; dirty.current = true }, [clip])
  useEffect(() => { restart.current = true }, [clip.action, clip.assetId])
  useEffect(() => {
    const element = host.current
    if (!element) return
    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    } catch {
      setError('3D预览无法启动，请开启浏览器图形加速')
      return
    }
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
    renderer.outputColorSpace = THREE.SRGBColorSpace
    element.appendChild(renderer.domElement)
    const scene = new THREE.Scene(),
      camera = new THREE.PerspectiveCamera(35, 1, 0.05, 100)
    camera.position.set(3, 1.7, 5)
    const controls = new OrbitControls(camera, renderer.domElement)
    controls.target.set(0, 1, 0)
    controls.enablePan = false
    controls.minDistance = 2.8
    controls.maxDistance = 9
    controls.update()
    controls.addEventListener('change', () => {
      dirty.current = true
    })
    scene.add(new THREE.HemisphereLight(0xffffff, 0x708094, 2.3))
    const light = new THREE.DirectionalLight(0xffffff, 3)
    light.position.set(3, 5, 4)
    scene.add(light)
    const floor = new THREE.Mesh(
      new THREE.CircleGeometry(1.3, 64),
      new THREE.MeshStandardMaterial({ color: 0x8090a0, transparent: true, opacity: 0.14, roughness: 1 })
    )
    floor.rotation.x = -Math.PI / 2
    floor.position.y = -0.015
    scene.add(floor)
    const cache = new DirectorMannequinRuntimeCache()
    let model: THREE.Object3D | undefined,
      disposed = false,
      raf = 0,
      last = performance.now(),
      elapsed = 0,
      lastDraw = 0
    void cache
      .load({ ...createDirectorActor(1), color: '#d6dce4', pose: {} }, false)
      .then((root) => {
        if (disposed) {
          disposeDirectorObjectTree(root)
          return
        }
        scene.add(root)
        model = findDirectorMannequinModelRoot(root) ?? undefined
        dirty.current = true
      })
      .catch((e) => {
        if (!disposed) setError(e.message)
      })
    const resize = new ResizeObserver(() => {
      const w = element.clientWidth,
        h = element.clientHeight
      if (!w || !h) return
      renderer.setSize(w, h)
      camera.aspect = w / h
      camera.updateProjectionMatrix()
      dirty.current = true
    })
    resize.observe(element)
    const draw = (now: number) => {
      const current = live.current
      const dt = Math.min((now - last) / 1000, 0.05)
      last = now
      if (restart.current) { elapsed = 0; restart.current = false }
      elapsed = (elapsed + dt * current.speed) % Math.max(1 / 30, current.end - current.start)
      if (model) applyDirectorMannequinPose(model, sampleDirectorActorAction({ ...current, speed: 1 }, current.start + elapsed))
      controls.update()
      if ((now - lastDraw >= 1000 / 30) || dirty.current) {
        renderer.render(scene, camera)
        lastDraw = now
        dirty.current = false
      }
      raf = requestAnimationFrame(draw)
    }
    raf = requestAnimationFrame(draw)
    return () => {
      disposed = true
      cancelAnimationFrame(raf)
      resize.disconnect()
      controls.dispose()
      disposeDirectorObjectTree(scene)
      cache.dispose()
      renderer.dispose()
      renderer.domElement.remove()
    }
  }, [])
  return (
    <div ref={host} className="director-motion-preview" role="img" aria-label="所选动作实时3D预览">
      {error ? <p role="alert">{error}</p> : null}
    </div>
  )
}
