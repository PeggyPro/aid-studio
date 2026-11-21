'use client'

import { withAppBasePath } from '@/utils/appBasePath'
import { useEffect, useRef, useState } from 'react'
import { studioEmotionExpression } from '@/utils/studio/studioEmotionExpression'

/** Demand-rendered local morph-target bust. Dispose every GPU resource when the dock closes. */
export function StudioEmotionHeadScene({ affinity, arousal }: { affinity: number; arousal: number }) {
  const host = useRef<HTMLDivElement>(null)
  const target = useRef({ affinity, arousal })
  const redraw = useRef<() => void>(() => {})
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    target.current = { affinity, arousal }
    redraw.current()
  }, [affinity, arousal])
  useEffect(() => {
    const element = host.current
    if (!element) return
    let disposed = false
    let release = () => {}
    setStatus('loading')
    void (async () => {
      const [THREE, { GLTFLoader }, { RoomEnvironment }] = await Promise.all([
        import('three'),
        import('three/examples/jsm/loaders/GLTFLoader.js'),
        import('three/examples/jsm/environments/RoomEnvironment.js')
      ])
      if (disposed) return
      const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
      renderer.setPixelRatio(Math.min(Math.max(devicePixelRatio, 1.5), 2))
      renderer.toneMapping = THREE.NeutralToneMapping
      renderer.domElement.setAttribute('aria-hidden', 'true')
      element.appendChild(renderer.domElement)
      const scene = new THREE.Scene()
      const camera = new THREE.PerspectiveCamera(30, 1, 0.01, 100)
      const model = new THREE.Group()
      // Plaster-bust lighting: soft room reflections, one upper-left key for form, a back rim for silhouette.
      const pmrem = new THREE.PMREMGenerator(renderer)
      const environment = new RoomEnvironment()
      scene.environment = pmrem.fromScene(environment, 0.04).texture
      scene.environmentIntensity = 0.28
      environment.dispose()
      pmrem.dispose()
      const key = new THREE.DirectionalLight(0xffffff, 2.4)
      key.position.set(-2.2, 2.8, 3)
      // Without self-shadowing the key leaks through the nose and lights the nostril interior.
      renderer.shadowMap.enabled = true
      renderer.shadowMap.type = THREE.PCFShadowMap
      key.castShadow = true
      key.shadow.mapSize.set(1024, 1024)
      Object.assign(key.shadow.camera, { left: -0.22, right: 0.22, top: 0.22, bottom: -0.22, near: 3, far: 6.5 })
      key.shadow.normalBias = 0.002
      key.shadow.radius = 6
      const rim = new THREE.DirectionalLight(0xffffff, 1.3)
      rim.position.set(3, 1.2, -2.5)
      scene.add(model, key, rim)
      const textures = new Set<import('three').Texture>([scene.environment])
      const materials = new Set<import('three').Material>()
      const geometries = new Set<import('three').BufferGeometry>()
      const meshes: import('three').Mesh[] = []
      const reduced = matchMedia('(prefers-reduced-motion: reduce)')
      let frame = 0,
        last = 0
      const current = { affinity: 0, arousal: 0 }
      const render = (time: number) => {
        frame = 0
        if (disposed) return
        const alpha = reduced.matches ? 1 : 1 - Math.exp(-Math.min((time - last) / 1000 || 0.016, 0.05) * 14)
        last = time
        current.affinity += (target.current.affinity - current.affinity) * alpha
        current.arousal += (target.current.arousal - current.arousal) * alpha
        const weights = studioEmotionExpression(current.affinity, current.arousal)
        for (const mesh of meshes) {
          for (const [name, index] of Object.entries(mesh.morphTargetDictionary ?? {})) {
            if (mesh.morphTargetInfluences)
              mesh.morphTargetInfluences[index] = weights[name.replace('blendShape1.', '')] ?? 0
          }
        }
        model.rotation.y = current.affinity * 0.065
        model.rotation.x = 0.03 - current.arousal * 0.035
        renderer.render(scene, camera)
        if (
          Math.abs(target.current.affinity - current.affinity) +
            Math.abs(target.current.arousal - current.arousal) >
          0.001
        ) {
          frame = requestAnimationFrame(render)
        }
      }
      const invalidate = () => {
        if (!frame && !disposed) frame = requestAnimationFrame(render)
      }
      redraw.current = invalidate
      const resize = () => {
        const width = element.clientWidth,
          height = element.clientHeight
        if (!width || !height) return
        renderer.setSize(width, height)
        camera.aspect = width / height
        camera.updateProjectionMatrix()
        invalidate()
      }
      const observer = new ResizeObserver(resize)
      observer.observe(element)
      const lost = (event: Event) => {
        event.preventDefault()
        setStatus('error')
      }
      renderer.domElement.addEventListener('webglcontextlost', lost)
      let released = false
      release = () => {
        if (released) return
        released = true
        cancelAnimationFrame(frame)
        observer.disconnect()
        redraw.current = () => {}
        renderer.domElement.removeEventListener('webglcontextlost', lost)
        geometries.forEach((g) => g.dispose())
        materials.forEach((m) => m.dispose())
        textures.forEach((t) => t.dispose())
        key.shadow.dispose()
        renderer.dispose()
        renderer.forceContextLoss()
        renderer.domElement.remove()
      }
      const gltf = await new GLTFLoader().loadAsync(withAppBasePath('/studio/emotion/head.glb'))
      gltf.scene.traverse((object) => {
        const mesh = object as import('three').Mesh
        if (!mesh.isMesh) return
        geometries.add(mesh.geometry)
        // Hide optional portrait hair if a styled GLB is loaded by mistake.
        const materialNames = (Array.isArray(mesh.material) ? mesh.material : [mesh.material]).map(
          (m) => m?.name ?? ''
        )
        if (
          mesh.name === 'SweptBob' ||
          /hair/i.test(mesh.name) ||
          materialNames.some((name) => /hair/i.test(name))
        ) {
          mesh.visible = false
          return
        }
        for (const material of Array.isArray(mesh.material) ? mesh.material : [mesh.material]) {
          materials.add(material)
          for (const value of Object.values(material)) if (value instanceof THREE.Texture) textures.add(value)
          material.side = THREE.FrontSide
        }
        mesh.castShadow = mesh.receiveShadow = true
        if (mesh.morphTargetInfluences) {
          mesh.morphTargetInfluences.fill(0)
          meshes.push(mesh)
        }
      })
      if (disposed) {
        geometries.forEach((g) => g.dispose())
        materials.forEach((m) => m.dispose())
        textures.forEach((t) => t.dispose())
        return
      }
      model.add(gltf.scene)
      model.updateMatrixWorld(true)
      const box = new THREE.Box3()
      model.traverse((object) => {
        const mesh = object as import('three').Mesh
        if (mesh.isMesh && mesh.visible)
          box.union(
            new THREE.Box3()
              .setFromBufferAttribute(mesh.geometry.attributes.position as import('three').BufferAttribute)
              .applyMatrix4(mesh.matrixWorld)
          )
      })
      const center = box.getCenter(new THREE.Vector3()),
        size = box.getSize(new THREE.Vector3())
      gltf.scene.position.sub(center)
      // A slightly raised lens, like a portrait bust study, keeps the nose base out of view.
      const distance = (size.y / (2 * Math.tan(THREE.MathUtils.degToRad(15)))) * 1.04
      const lookAt = new THREE.Vector3(0, size.y * 0.04, 0)
      const elevation = THREE.MathUtils.degToRad(10)
      camera.position.set(0, lookAt.y + Math.sin(elevation) * distance, Math.cos(elevation) * distance)
      camera.lookAt(lookAt)
      resize()
      setStatus('ready')
    })().catch(() => {
      if (!disposed) {
        release()
        setStatus('error')
      }
    })
    return () => {
      disposed = true
      release()
    }
  }, [attempt])
  return (
    <div className="studio-emotion-head" role="group" aria-label="人物表情动态预览">
      <div ref={host} className="studio-emotion-head__scene" />
      {status !== 'ready' && (
        <div className="studio-emotion-head__status" role="status">
          {status === 'loading' ? (
            '正在加载表情预览…'
          ) : (
            <>
              <span>表情预览暂不可用</span>
              <button type="button" onClick={() => setAttempt((v) => v + 1)}>
                重新加载
              </button>
            </>
          )}
        </div>
      )}
      <span>表情示意</span>
    </div>
  )
}
