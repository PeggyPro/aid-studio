export type StudioAudioOperation = 'TRIM' | 'SPEED' | 'CUSTOM_SPLIT'
export interface StudioAudioEdit {
  operation: StudioAudioOperation
  sourceNodeId: string
  sourceAudioUrl: string
  sourceTitle: string
  sourceRecordId?: number
  durationSeconds: number
  startSeconds: number
  endSeconds: number
  speed: number
  instruction: string
}
export interface StudioAudioOperationRequest {
  nodeId: string
  targetNodeId: string
  operation: StudioAudioOperation
  sourceAudioUrl: string
  sourceRecordId?: number
  projectId?: number
  episodeId?: number
  parameters: {
    clipRange?: { startSeconds: number; endSeconds: number }
    speed?: number
    preservePitch?: boolean
    instruction?: string
  }
}
export interface StudioAudioOutput { audioUrl: string; durationSeconds?: number; recordId?: number }
