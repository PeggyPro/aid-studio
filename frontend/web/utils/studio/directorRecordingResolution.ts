export function directorRecordingDimensions(value: string) {
  const match = /^(\d{3,4})x(\d{3,4})$/.exec(value)
  if (!match) throw new Error('请选择有效的录制清晰度')
  const width = Number(match[1]), height = Number(match[2])
  if (width < 320 || height < 240 || width > 1920 || height > 1080 || width % 2 || height % 2)
    throw new Error('当前清晰度超出已验证的录制范围')
  return { width, height }
}
