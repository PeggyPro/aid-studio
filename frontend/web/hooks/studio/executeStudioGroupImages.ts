import { message } from 'antd'
import {
  loadStudioImageSubmitPools,
  submitStudioImageNode
} from '@/hooks/studio/studioImageNodeSubmit'
import { useStudioUiStore } from '@/stores/studioUi'
import { listGroupExecutableNodeIds } from '@/utils/studio/studioGroupActions'

/**
 * 整组执行：按 Composer 同源 `submitStudioImageNode` 串行提交。
 * 单个失败不阻断其余。
 */
export async function executeStudioGroupImages(childIds: string[]): Promise<number> {
  const store = useStudioUiStore.getState()
  const ids = listGroupExecutableNodeIds(store.nodes, childIds)
  if (!ids.length) {
    message.warning('没有可执行的节点')
    return 0
  }
  message.info(`开始整组执行（${ids.length} 个节点）`)
  let pools
  try {
    pools = await loadStudioImageSubmitPools()
  } catch {
    message.error('读取模型池失败')
    return 0
  }
  let ok = 0
  for (const id of ids) {
    try {
      if (await submitStudioImageNode(id, pools)) ok += 1
    } catch {
      // 单个失败继续
    }
  }
  if (ok) message.success(`整组执行完成：成功 ${ok}/${ids.length}`)
  else message.warning('整组执行未成功提交任何节点')
  return ok
}
