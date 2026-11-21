import type { StudioEmotionFace } from '@/types/studio'

export const STUDIO_EMOTION_LABELS = [
  ['欣然喜悦', '兴高采烈', '亢奋激昂', '难以置信', '受惊后退'],
  ['温情脉脉', '愉悦开怀', '紧张不安', '戒备警惕', '疏远回避'],
  ['亲近依恋', '柔和友善', '淡然自若', '冷淡疏离', '拒人千里'],
  ['温柔安抚', '从容平和', '沉静内敛', '淡漠疏远', '冷眼旁观'],
  ['安详宁静', '恬淡自在', '平静淡然', '漠然处之', '疏离冷峻']
] as const
const clamp = (v: number) => Math.max(-1, Math.min(1, Number.isFinite(v) ? v : 0))
export function emotionGridPoint(affinity: number, arousal: number) {
  const column = Math.round((clamp(affinity) + 1) * 2)
  const row = Math.round((1 - clamp(arousal)) * 2)
  return {
    column,
    row,
    affinity: column / 2 - 1,
    arousal: 1 - row / 2,
    label: STUDIO_EMOTION_LABELS[row][column]
  }
}
export function buildStudioEmotionPrompt(face: StudioEmotionFace): string {
  const point = emotionGridPoint(face.affinity, face.arousal)
  const { x, y, w, h } = face.rect
  const percent = (v: number) => `${(v * 100).toFixed(1)}%`
  return `仅调整原图指定人物的面部微表情为「${point.label}」。目标脸部位于原图左上角为原点的矩形区域：左 ${percent(x)}、上 ${percent(y)}、宽 ${percent(w)}、高 ${percent(h)}；以该区域中的人脸定位目标，区域不是裁剪指令。亲近到疏离程度 ${point.affinity}，平静到激动程度 ${point.arousal}（范围 -1 到 1）。表情自然，眉眼和嘴部协调。保持人物身份、五官比例、发型、妆造、服装、身体姿态、背景、构图、画幅与光影不变；其他人物及区域保持不变。不添加框线、文字或新人物，输出完整原画幅。`
}
