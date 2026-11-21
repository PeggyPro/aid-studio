export type StudioVideoCaptureFrame = '首帧' | '当前帧' | '尾帧'

export const STUDIO_CAPTURE_VIDEO_FRAME_EVENT = 'studio:capture-video-frame'

export function dispatchStudioCaptureVideoFrame(nodeId: string, frame: StudioVideoCaptureFrame) {
  window.dispatchEvent(new CustomEvent(STUDIO_CAPTURE_VIDEO_FRAME_EVENT, {
    detail: { nodeId, frame }
  }))
}

export function parseStudioCaptureVideoFrameEvent(event: Event): {
  nodeId?: string
  frame?: StudioVideoCaptureFrame
} {
  return (event as CustomEvent<{ nodeId?: string; frame?: StudioVideoCaptureFrame }>).detail ?? {}
}
