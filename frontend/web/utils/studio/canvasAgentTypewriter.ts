/** 空输入时轮播的示例，只出现在占位层，不会写入真实输入。 */
export const CANVAS_AGENT_TYPEWRITER_PROMPTS = [
  '把这个想法拆成可编辑的镜头分镜',
  '检查当前画布还缺哪些内容',
  '输入 / 选择 Skill，按 Enter 发送'
] as const

export function shouldPlayCanvasAgentTypewriter(input: {
  /** 底部开场动画已经结束。 */
  introDone: boolean
  value: string
  focused: boolean
  /** 输入框可编辑。未保存项目或本轮进行中时为 false。 */
  enabled: boolean
  reducedMotion: boolean
}) {
  return input.introDone
    && input.enabled
    && !input.focused
    && !input.reducedMotion
    && input.value.trim() === ''
}
