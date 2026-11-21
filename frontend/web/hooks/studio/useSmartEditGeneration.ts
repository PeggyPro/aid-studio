'use client'

import { useCallback } from 'react'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'

export function useSmartEditGeneration() {
  const runtime = useCanvasProjectRuntime()
  return useCallback(async (nodeId: string): Promise<boolean> => {
    const node = useStudioUiStore.getState().nodes.find((item) => item.id === nodeId)
    if (!runtime || !node) throw new Error('请在已保存的画布项目中生成素材')
    if (!runtime.canEdit || !runtime.canMutate) throw new Error('当前画布不可编辑')
    if (node.data.kind === 'voice') {
      // 现有 Canvas generation 只声明 text/image/video；禁止把 voice 错发到 video 或 Mock。
      const errorMessage = '画布配音生成服务尚未接入，请连接已有音频后重试'
      useStudioUiStore.getState().updateNodeData(nodeId, { status: 'failed', errorMessage })
      throw new Error(errorMessage)
    }
    if (node.data.kind !== 'video') throw new Error('智能剪辑只能请求普通视频或配音素材')
    return runtime.generateNode('video', nodeId, node.data.prompt)
  }, [runtime])
}
