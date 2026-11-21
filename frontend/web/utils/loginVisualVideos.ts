export const LOGIN_VISUAL_VIDEO_URLS = [
  '/media/login/1.mp4',
  '/media/login/2.mp4',
  '/media/login/3.mp4',
  '/media/login/4.mp4'
] as const

export function nextLoginVisualVideoIndex(
  index: number,
  length = LOGIN_VISUAL_VIDEO_URLS.length
): number {
  if (length <= 0) return 0
  return (index + 1) % length
}
