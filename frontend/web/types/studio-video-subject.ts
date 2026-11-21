import type { StudioLayerSplitRect, StudioVideoClipRange } from './studio'

export type StudioVideoSubjectMode = 'remove' | 'modify' | 'replace' | 'matting'
export type StudioVideoSubjectTool = 'point' | 'rectangle' | 'brush' | 'eraser'
export interface SubjectPoint { x: number; y: number }
export interface SubjectStroke {
  tool: 'brush' | 'eraser'
  /** Diameter relative to the shorter displayed frame dimension. */
  size: number
  points: SubjectPoint[]
}
export interface SubjectSelection {
  tool: 'point' | 'rectangle' | 'brush'
  point?: SubjectPoint
  rect: StudioLayerSplitRect
  strokes: SubjectStroke[]
}
export interface SubjectCandidate {
  id: string
  label: string
  rect: StudioLayerSplitRect
  thumbnailUrl: string
  maskUrl?: string
}
export interface StudioVideoSubjectMark {
  id: string
  frameSeconds: number
  selection: SubjectSelection
  candidates: SubjectCandidate[]
  candidateId: string
  instruction: string
  replacementUrl?: string
  replacementNodeId?: string
  replacementResourceId?: string
  confirmed: boolean
}
export interface StudioVideoSubjectEdit {
  mode: StudioVideoSubjectMode
  sourceNodeId: string
  sourceVideoUrl: string
  sourceTitle: string
  sourceRecordId?: number
  range: StudioVideoClipRange
  marks: StudioVideoSubjectMark[]
}
export interface StudioVideoSubjectDetectRequest {
  sourceVideoUrl: string
  sourceNodeId: string
  frameSeconds: number
  selection: SubjectSelection
  projectId?: number
  episodeId?: number
}
export interface StudioVideoSubjectDetectData {
  taskId?: number
  candidates?: SubjectCandidate[]
}
