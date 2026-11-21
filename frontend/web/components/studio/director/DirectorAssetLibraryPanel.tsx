'use client'

import { DirectorPanelDescription } from './DirectorPanelDescription'

import { useEffect, useRef, type ReactNode } from 'react'
import { AppstoreAddOutlined, DeleteOutlined } from '@ant-design/icons'
import { App, Button } from 'antd'
import type { DirectorModelAsset, DirectorPropShape, DirectorSceneData } from '@/types/studio'
import { createDirectorProp, DIRECTOR_PROP_SHAPES } from '@/utils/studio/directorScene'
import { addDirectorModelAssetInstance } from '@/utils/studio/directorModelAssets'
import { DirectorBuiltinModelLibrary } from './DirectorBuiltinModelLibrary'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'

interface DirectorAssetLibraryPanelProps {
  scene: DirectorSceneData
  onChange: (scene: DirectorSceneData) => void
  onSelectObject: (id: string | null) => void
  children?: ReactNode
  primitives?: ReactNode
}

export function DirectorAssetLibraryPanel({ scene, onChange, onSelectObject, children, primitives }: DirectorAssetLibraryPanelProps) {
  const { message } = App.useApp()
  const scrollRoot = useRef<HTMLDivElement>(null)
  const sceneRef = useRef(scene)
  useEffect(() => { sceneRef.current = scene }, [scene])
  const assets = (scene.modelAssets ?? []).filter(asset => asset.source !== 'builtin')
  const addShape = (shape: DirectorPropShape) => {
    const current = sceneRef.current
    const object = createDirectorProp(current.objects.filter(item => item.kind === 'prop').length + 1, shape)
    const next = touchScene({ ...current, objects: [...current.objects, object] })
    sceneRef.current = next
    onChange(next)
    onSelectObject(object.id)
  }

  const addAssetToScene = (asset: DirectorModelAsset) => {
    try {
      const result = addDirectorModelAssetInstance(sceneRef.current, asset)
      sceneRef.current = result.scene
      onChange(result.scene)
      onSelectObject(result.object.id)
    } catch (error) { message.error(error instanceof Error ? error.message : '模型添加失败') }
  }

  const removeAsset = (assetId: string) => {
    onChange(touchScene({
      ...scene,
      modelAssets: (scene.modelAssets ?? []).filter((asset) => asset.id !== assetId),
      objects: scene.objects.map((object) => object.modelAssetId === assetId
        ? { ...object, modelAssetId: undefined }
        : object)
    }))
    message.success('模型已从资产库移除，场景实例已恢复为基础模型')
  }

  return (
    <div ref={scrollRoot} className="director-tool-panel director-tool-panel--scroll">
      {children}
      <section className="director-tool-section">
        <h3>几何模型</h3>
        <div className="director-choice-list">
          {DIRECTOR_PROP_SHAPES.map(item => <button key={item.value} type="button" onClick={() => addShape(item.value)}><AppstoreAddOutlined aria-hidden /><span>{item.label}</span><em>+</em></button>)}
        </div>
      </section>
      {primitives}
      <DirectorPanelDescription>模型由管理员统一上传和管理，可重复添加到场景。</DirectorPanelDescription>
      <section className="director-tool-section">
        <h3>官方模型</h3>
        <DirectorBuiltinModelLibrary scene={scene} onChange={onChange} onSelectObject={onSelectObject} scrollRoot={scrollRoot} />
      </section>
      <section className="director-tool-section">
        <h3>项目模型（{assets.length}）</h3>
        {assets.length ? (
          <div className="director-project-models">
            {assets.map((asset) => (
              <article key={asset.id} className="director-project-model">
                <AppstoreAddOutlined aria-hidden />
                <button type="button" className="director-project-model__add" aria-label={`添加项目模型${asset.name}`} onClick={() => addAssetToScene(asset)}>
                  <strong>{asset.name}</strong>
                  <small>{asset.format.toUpperCase()} · {asset.kind === 'actor' ? '角色' : '道具'}</small>
                </button>
                <StudioCanvasTooltip title="移除模型">
                  <Button type="text" size="small" danger icon={<DeleteOutlined />} aria-label={`移除${asset.name}`} onClick={() => removeAsset(asset.id)} />
                </StudioCanvasTooltip>
              </article>
            ))}
          </div>
        ) : (
          <div className="director-panel-empty"><AppstoreAddOutlined /><span>还没有导入模型</span></div>
        )}
      </section>
    </div>
  )
}

function touchScene(scene: DirectorSceneData): DirectorSceneData {
  return { ...scene, updatedAt: new Date().toISOString() }
}
