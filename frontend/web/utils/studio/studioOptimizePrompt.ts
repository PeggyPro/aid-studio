export async function mockOptimizePrompt(input: string): Promise<{ thinking: string; result: string }> {
  const text = input.trim()
  if (!text) return { thinking: '', result: '' }
  return {
    thinking: '正在理清主体、光线与镜头语言。',
    result: `${text}，电影感光线，主体清晰，构图稳定。`
  }
}
