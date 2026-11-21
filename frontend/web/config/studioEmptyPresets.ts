import type { StudioNodeData, StudioNodeKind } from '@/types/studio'
import type { StudioEmptyPresetId } from './studioNodeEmptyNextSteps'

export interface StudioEmptyPresetNode {
  role: string
  kind: StudioNodeKind
  offset: { x: number; y: number }
  data: Partial<StudioNodeData>
}

export interface StudioEmptyPreset {
  id: StudioEmptyPresetId
  anchorKind: StudioNodeKind
  anchorRole: string
  anchorPatch?: Partial<StudioNodeData>
  nodes: readonly StudioEmptyPresetNode[]
  edges: ReadonlyArray<{ sourceRole: string; targetRole: string }>
  selectRole: string
  sourceScriptRole?: string
}

const IMAGE_TO_PROMPT = '根据图片生成结构化中文提示词，包括主体描述、环境、光影、镜头语言、风格关键词。'
const SAMPLE_SCRIPT = '雨夜的旧车站里，年轻女孩循着一段断续的旋律寻找失联多年的父亲。广播忽然响起，而站台尽头出现了熟悉的背影。'

function sampleSourceImage(): string {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="420" viewBox="0 0 640 420"><defs><linearGradient id="sky" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#60708d"/><stop offset="1" stop-color="#c9a77d"/></linearGradient></defs><rect width="640" height="420" fill="url(#sky)"/><circle cx="500" cy="92" r="54" fill="#fff2c4" opacity=".8"/><path d="M0 310 135 190l92 82 96-150 112 142 92-92 113 138v110H0Z" fill="#26364d" opacity=".84"/><text x="28" y="384" fill="#fff" font-family="Arial,sans-serif" font-size="22" opacity=".88">可替换的高清示例源图</text></svg>'
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

export const STUDIO_EMPTY_PRESETS: Record<StudioEmptyPresetId, StudioEmptyPreset> = {
  'text-to-video': {
    id: 'text-to-video', anchorKind: 'text', anchorRole: 'text',
    anchorPatch: { emptyNextStepConsumed: true },
    nodes: [{ role: 'video', kind: 'video', offset: { x: 370, y: 0 }, data: { title: '文生视频', prompt: '根据引用文本生成叙事连贯、镜头稳定的视频。', status: 'empty' } }],
    edges: [{ sourceRole: 'text', targetRole: 'video' }], selectRole: 'video'
  },
  'image-to-prompt': {
    id: 'image-to-prompt', anchorKind: 'text', anchorRole: 'text',
    anchorPatch: { title: '图片反推提示词', prompt: IMAGE_TO_PROMPT, status: 'empty', mediaKind: 'text', emptyNextStepConsumed: true },
    nodes: [{ role: 'image', kind: 'image', offset: { x: -330, y: 0 }, data: { title: '上传参考图片', imagePurpose: 'reference', status: 'empty' } }],
    edges: [{ sourceRole: 'image', targetRole: 'text' }], selectRole: 'text'
  },
  'text-to-audio': {
    id: 'text-to-audio', anchorKind: 'text', anchorRole: 'text',
    anchorPatch: { emptyNextStepConsumed: true },
    nodes: [{ role: 'audio', kind: 'voice', offset: { x: 370, y: 0 }, data: { title: '文字生音乐', prompt: '根据引用文本生成匹配叙事情绪、节奏和氛围的音频。', status: 'empty' } }],
    edges: [{ sourceRole: 'text', targetRole: 'audio' }], selectRole: 'audio'
  },
  'image-to-image': {
    id: 'image-to-image', anchorKind: 'image', anchorRole: 'target',
    anchorPatch: { title: '图生图', imagePurpose: 'generic', prompt: '参考上传图片生成主体一致、细节清晰的新画面。', status: 'empty', emptyNextStepConsumed: true },
    nodes: [{ role: 'source', kind: 'image', offset: { x: -330, y: 0 }, data: { title: '图生图源图', imagePurpose: 'reference', status: 'empty' } }],
    edges: [{ sourceRole: 'source', targetRole: 'target' }], selectRole: 'target'
  },
  'image-hd': {
    id: 'image-hd', anchorKind: 'image', anchorRole: 'target',
    anchorPatch: { title: '图片高清', imagePurpose: 'hd', prompt: '提升图片清晰度并保留原始构图与主体细节。', status: 'empty', emptyNextStepConsumed: true },
    nodes: [{ role: 'source', kind: 'image', offset: { x: -330, y: 0 }, data: { title: '高清示例源图', imagePurpose: 'reference', mediaKind: 'image', mediaUrl: sampleSourceImage(), mediaAspectRatio: 640 / 420, resultSummary: '默认示例，可上传替换', status: 'success' } }],
    edges: [{ sourceRole: 'source', targetRole: 'target' }], selectRole: 'target'
  },
  'image-to-video': {
    id: 'image-to-video', anchorKind: 'video', anchorRole: 'video',
    anchorPatch: { title: '图生视频', prompt: '根据引用图片生成自然连贯的动态镜头。', status: 'empty', emptyNextStepConsumed: true },
    nodes: [{ role: 'image', kind: 'image', offset: { x: -330, y: 0 }, data: { title: '视频参考图', imagePurpose: 'reference', status: 'empty' } }],
    edges: [{ sourceRole: 'image', targetRole: 'video' }], selectRole: 'video'
  },
  'first-frame-to-video': {
    id: 'first-frame-to-video', anchorKind: 'video', anchorRole: 'video',
    anchorPatch: { title: '首帧生视频', prompt: '以首帧画面为起点生成主体一致、运动连贯的视频。', status: 'empty', emptyNextStepConsumed: true },
    nodes: [{ role: 'first', kind: 'image', offset: { x: -330, y: 0 }, data: { title: '首帧图片', imagePurpose: 'storyboard', status: 'empty' } }],
    edges: [{ sourceRole: 'first', targetRole: 'video' }], selectRole: 'video'
  },
  'first-last-frame-to-video': {
    id: 'first-last-frame-to-video', anchorKind: 'video', anchorRole: 'video',
    anchorPatch: { title: '首尾帧生视频', prompt: '在首帧与尾帧之间生成自然连贯、主体稳定的过渡视频。', generationOptions: { endFrameEnabled: true }, status: 'empty', emptyNextStepConsumed: true },
    nodes: [
      { role: 'first', kind: 'image', offset: { x: -330, y: -105 }, data: { title: '首帧图片', imagePurpose: 'storyboard', status: 'empty' } },
      { role: 'last', kind: 'image', offset: { x: -330, y: 105 }, data: { title: '尾帧图片', imagePurpose: 'storyboard', status: 'empty' } }
    ],
    edges: [{ sourceRole: 'first', targetRole: 'video' }, { sourceRole: 'last', targetRole: 'video' }], selectRole: 'video'
  },
  'audio-image-to-video': {
    id: 'audio-image-to-video', anchorKind: 'voice', anchorRole: 'audio',
    anchorPatch: { emptyNextStepConsumed: true },
    nodes: [
      { role: 'image', kind: 'image', offset: { x: 0, y: 370 }, data: { title: '视频画面参考', imagePurpose: 'reference', status: 'empty' } },
      { role: 'video', kind: 'video', offset: { x: 370, y: 70 }, data: { title: '音频生视频', prompt: '结合引用音频的节奏与图片主体生成同步视频。', status: 'empty' } }
    ],
    edges: [{ sourceRole: 'audio', targetRole: 'video' }, { sourceRole: 'image', targetRole: 'video' }], selectRole: 'video'
  },
  'script-to-storyboard': {
    id: 'script-to-storyboard', anchorKind: 'storyboard_script', anchorRole: 'storyboard',
    anchorPatch: { title: '剧本生成分镜脚本', prompt: '基于引用剧本源拆解镜头，生成节奏清晰、可执行的分镜脚本。', status: 'empty', mediaKind: 'text', emptyNextStepConsumed: true },
    nodes: [{ role: 'script', kind: 'text', offset: { x: -370, y: 0 }, data: { title: '默认剧本源', documentContent: SAMPLE_SCRIPT, mediaKind: 'text', status: 'success' } }],
    edges: [{ sourceRole: 'script', targetRole: 'storyboard' }], selectRole: 'storyboard', sourceScriptRole: 'script'
  },
  'characters-to-storyboard': {
    id: 'characters-to-storyboard', anchorKind: 'storyboard_script', anchorRole: 'storyboard',
    anchorPatch: { title: '角色生成分镜脚本', prompt: '围绕引用角色设计一段关系清晰、冲突明确的剧情，并拆解为可执行分镜。', status: 'empty', mediaKind: 'text', emptyNextStepConsumed: true },
    nodes: [
      { role: 'character-a', kind: 'image', offset: { x: -330, y: -105 }, data: { title: '角色 A', imagePurpose: 'character', status: 'empty' } },
      { role: 'character-b', kind: 'image', offset: { x: -330, y: 105 }, data: { title: '角色 B', imagePurpose: 'character', status: 'empty' } }
    ],
    edges: [{ sourceRole: 'character-a', targetRole: 'storyboard' }, { sourceRole: 'character-b', targetRole: 'storyboard' }], selectRole: 'storyboard'
  }
}
