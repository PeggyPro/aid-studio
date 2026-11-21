import type { SVGProps } from 'react'

/** One 24-unit, rounded 1.6-stroke family; names describe product intent. */
const paths = {
  model: 'M8 5h8a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3ZM9 9h6v6H9V9ZM9 2v3m6-3v3M9 19v3m6-3v3M2 9h3m-3 6h3m14-6h3m-3 6h3',
  cameraMove: 'M5 4h9a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm11 4 5-2v7l-5-2M4 20h14m-3-3 3 3-3 3',
  cameraShot: 'M5 5h3l2-2h4l2 2h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Zm11 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  importText: 'M8 7V5a2 2 0 0 1 2-2h5l5 5v11a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-2M15 3v5h5M2 12h11m-3-3 3 3-3 3M13 17h3',
  videoMode: 'M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm5 4 6 4-6 4V8Z',
  agentChat: 'M7 4h10a4 4 0 0 1 4 4v5a4 4 0 0 1-4 4H9l-5 4v-5a4 4 0 0 1-1-3V8a4 4 0 0 1 4-4Zm1 5h8m-8 4h5',
  reconnect: 'M20 11a8 8 0 0 0-14-5L3 9m0-5v5h5M4 13a8 8 0 0 0 14 5l3-3m-5 0h5v5',
  connection: 'M9 8 7 6a3 3 0 0 0-4 4l3 3m9 3 2 2a3 3 0 0 0 4-4l-3-3M3 3l18 18M11 3h3m-2 18h-3',
  close: 'm6 6 12 12M18 6 6 18',
  skillSettings: 'M4 6h16M4 12h16M4 18h16M8 3v6m8 0v6m-7 0v6',
  sun: 'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM12 1v3M12 20v3M1 12h3M20 12h3M4 4l2 2M18 18l2 2M4 20l2-2M18 6l2-2',
  angle: 'M22 12a10 5 0 1 1-20 0 10 5 0 0 1 20 0ZM12 3v18m-3-3 3 3 3-3',
  link: 'm10 8 3-3a4 4 0 0 1 6 6l-3 3M14 16l-3 3a4 4 0 0 1-6-6l3-3M8 16l8-8',
  download: 'M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5',
  crop: 'M7 3v14h14M3 7h14v14',
  edit: 'm15 4 5 5-11 11H4v-5L15 4ZM12 7l5 5',
  layers: 'm12 3 10 6-10 6L2 9l10-6ZM2 13l10 6 10-6M2 17l10 6 10-6',
  select: 'm5 3 14 8-7 2-3 7-4-17Z',
  hand: 'M8 11V6a2 2 0 0 1 4 0v5-7a2 2 0 0 1 4 0v7-5a2 2 0 0 1 4 0v9c0 4-3 6-7 6-3 0-5-2-7-5l-3-4a2 2 0 0 1 3-2l2 2',
  undo: 'M8 4 3 9l5 5M3 9h11a6 6 0 0 1 0 12',
  redo: 'm16 4 5 5-5 5M21 9H10a6 6 0 0 0 0 12',
  expand: 'M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5',
  composerRestore: 'M3 8h5V3M16 3v5h5M21 16h-5v5M8 21v-5H3',
  composerGrow: 'M5 3h14M5 21h14M12 6v12m-3-9 3-3 3 3m-6 6 3 3 3-3',
  composerShorten: 'M5 3h14M5 21h14M12 4v6m-3-3 3 3 3-3M12 20v-6m-3 3 3-3 3 3',
  shortcuts:
    'M3 6h18a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Zm2 3h2m2 0h2m2 0h2m2 0h2M6 12h2m2 0h2m2 0h2m2 0h2M8 15h8',
  library: 'M4 6h5l2 2h9v10H4V6Zm0 4h16',
  save: 'M4 3h13l4 4v14H3V3h1ZM7 3v6h10V3M7 21v-8h10v8',
  sparkle: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3ZM20 2v4M18 4h4',
  imageUpscale: 'M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5M8 16l8-8m-5 0h5v5',
  videoUpscale: 'M3 5h16v14H3V5Zm16 5 3-2v8l-3-2M11 16V8m-3 3 3-3 3 3',
  imageExpand: 'M8 8h8v8H8V8ZM4 8V4h4m8 0h4v4m0 8v4h-4M8 20H4v-4',
  repaint: 'M4 19h8m-7-4 9-9 4 4-9 9H5v-4ZM13 7l4 4M17 3l4 4',
  eraseRegion: 'm4 15 9-10 7 7-7 8H8l-4-5Zm4 5h12M9 10l7 7',
  matting: 'M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM5 20c0-4 3-6 7-6s7 2 7 6M3 6V3h3m12 0h3v3M3 18v3h3m12 0h3v-3',
  touchEdit: 'M12 3v3m0 12v3M3 12h3m12 0h3M12 7a5 5 0 1 0 5 5M12 10v4m-2-2h4',
  imageEdit: 'M3 5h18v14H3V5Zm4 10 3-3 3 2 3-4m1 4 4 4-5 2 1-5Z',
  gridSplit: 'M3 3h18v18H3V3Zm9 0v18M3 12h18',
  imageSetting: 'M5 3h14v18H5V3Zm3 4h8m-8 4h8m-8 5 2-2 2 2 2-3 2 3',
  subtitleRemove: 'M3 6h18v12H3V6Zm4 6h10M8 15h8M4 20 20 4',
  subtitleRegion: 'M3 8V5h4m10 0h4v3M3 16v3h4m10 0h4v-3M7 12h10m-8 3h6',
  videoDepth: 'M3 5h18v14H3V5Zm3 11c2-6 4-8 7-8s5 3 5 6M8 16c1-3 3-5 5-5s3 1 3 3m-5 2c0-1 1-2 2-2',
  videoTrim: 'M4 5h16v14H4V5Zm4 0v14m8-14v14M4 9h4m8 0h4M4 15h4m8 0h4m-6-4 4 2-4 2v-4Z',
  videoCrop: 'M5 3v15h16M3 6h15v15M10 10h4v4h-4z',
  audioVideoSeparate: 'M3 4h12v7H3V4Zm3 12h2l2-3 2 6 2-3h2m2-8 3-2v7l-3-2M3 21h18',
  vocals: 'M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3ZM6 10a6 6 0 0 0 12 0m-6 6v5m-4 0h8',
  backgroundAudio:
    'M8 17V5l11-2v12M8 8l11-2M8 17a3 2 0 1 1-6 0 3 2 0 0 1 6 0Zm11-2a3 2 0 1 1-6 0 3 2 0 0 1 6 0Z',
  reshoot: 'M4 5h16v14H4V5Zm4 0v14m8-14v14M4 10h4m8 0h4m-11 4 3-3 3 3m-3-3v5',
  continuation: 'M3 5h13v14H3V5Zm4 0v14m5-9 3 2-3 2m5-2h5m-3-3 3 3-3 3',
  subjectEdit: 'M12 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM5 19c0-4 3-6 7-6s7 2 7 6m0-11 3 3-4 4-2-1 1-2 4-4Z',
  subjectRemove: 'M11 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM4 20c0-4 3-7 7-7 2 0 4 1 5 2m2 4h5',
  subjectModify: 'M10 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM3 20c0-4 3-7 7-7 2 0 3 0 4 1m1 7 6-6 2 2-6 6h-2v-2Z',
  subjectReplace:
    'M8 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM2 19c0-4 2-6 6-6m6-9a3 3 0 1 1 0 6m-1 3c4 0 7 2 7 6M8 17h8m-3-3 3 3-3 3',
  subjectMatting: 'M12 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM5 19c0-4 3-6 7-6s7 2 7 6M3 3h5m8 0h5M3 21h5m8 0h5',
  audioTrim: 'M3 5v14m18-14v14M6 12h2l2-5 3 10 2-5h3',
  audioSpeed: 'M4 17a8 8 0 1 1 16 0M12 17l5-7M8 17h8M7 10l-1-1m12 0-1 1',
  audioSplit: 'M3 12h3l2-5 3 10 2-5h3l2-4 2 4h2M8 3v3m0 12v3M17 3v3m0 12v3',
  imageGlobalLight: 'M12 5a7 7 0 1 0 0 14 7 7 0 0 0 0-14ZM12 5v14M5 12h14m-4-7 3-3m1 6 3-1',
  imageRegenerate: 'M4 11a8 8 0 1 1 2 7M4 5v6h6M9 13l3 2 3-3',

  assistantUniversal:
    'M12 3a9 9 0 1 0 9 9M12 7l1.4 3.6L17 12l-3.6 1.4L12 17l-1.4-3.6L7 12l3.6-1.4L12 7M18 2v5m-2.5-2.5h5',
  assistantCraft:
    'M7 4h12v13a3 3 0 0 1-3 3H6a3 3 0 0 1 0-6h1V4Zm0 0H5a2 2 0 0 0-2 2v2h4M7 14h9v3a3 3 0 0 0 3 3M10 8h6m-6 3h4',
  assistantAdapt: 'M4 3h10l4 4v4M4 3v18h8M14 3v4h4M7 8h3m-3 4h4m3 5 5-5 3 3-5 5h-3v-3Z',
  skill: 'M4 4h6v6H4V4Zm10 0h6v6h-6V4ZM4 14h6v6H4v-6Zm13-1v8m-4-4h8',
  idea: 'M5 4h10a5 5 0 0 1 5 5v3a5 5 0 0 1-5 5H9l-4 4v-4a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3Zm6 3 1.1 2.9L15 11l-2.9 1.1L11 15l-1.1-2.9L7 11l2.9-1.1L11 7',
  upload: 'M5 13v7h14v-7M12 16V3m-4 4 4-4 4 4M5 13h3m8 0h3',
  summary: 'M4 5c3-1 5-1 8 1 3-2 5-2 8-1v14c-3-1-5-1-8 1-3-2-5-2-8-1V5Zm8 1v14M7 9h2m-2 4h2m6-4h2m-2 4h2',
  outline: 'M4 4h6v4H4V4Zm10 6h6v4h-6v-4ZM4 16h6v4H4v-4Zm3-8v4h7m3 2v4h-7',
  characters:
    'M9 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM3 20v-2a6 6 0 0 1 12 0v2M16 4a3 3 0 0 1 0 6m2 4a5 5 0 0 1 3 4v2',
  scene: 'M4 6h16v14H4V6Zm0 0 14-3 1 3M7 5l2 2m3-3 2 2M4 10h16m-10 3 5 3-5 3v-6Z',
  polish: 'M4 20h10M6 15l8-8 3 3-8 8H6v-3Zm7-7 3 3M19 2v6m-3-3h6',
  rewrite: 'M4 4h10v16H4V4Zm3 4h4m-4 4h3m-3 4h4m6-10h4l-2-2m2 2-2 2m2 10h-4l2 2m-2-2 2-2',
  manual: 'M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm2 8 3 3 5-6',
  auto: 'M4 10a8 8 0 0 1 14-4l2 2m0-4v4h-4M20 14a8 8 0 0 1-14 4l-2-2m0 4v-4h4m4-8 1.2 2.8L16 12l-2.8 1.2L12 16l-1.2-2.8L8 12l2.8-1.2L12 8',
  addNode: 'M4 4h9v9H4V4Zm3 9v7h13v-7M16 7h6m-3-3v6',
  newChat: 'M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H9l-5 2V7a3 3 0 0 1 3-3Zm5 4v8m-4-4h8',
  history: 'M4 8a9 9 0 1 1-1 7M4 3v5h5m3-1v6l4 2',
  nodeImage:
    'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Zm-2 13 5-5 4 4 4-6 5 7M7 7h.01',
  nodeVideo: 'M4 4h16v16H4V4Zm0 4h16M8 4v4m8-4v4m-6 4 6 3-6 3v-6Z',
  nodeFrames: 'M3 5h18v14H3V5Zm6 0v14m6-14v14M5 8h2m4 7h2m4-7h2',
  nodeEdit: 'M4 4h16v16H4V4Zm4 0v16M4 8h4m-4 8h4m4-7 6 6m0-6-6 6',
  nodeText: 'M5 3h10l4 4v14H5V3Zm10 0v4h4M8 11h8m-8 4h8m-8 3h4',
  nodeStoryboard: 'M3 4h18v16H3V4Zm9 0v16M3 12h18M6 8h3m6 8h3',
  nodeDirector: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm4 5-3 5-5 3 3-5 5-3Z',
  nodeStyle:
    'M12 3a9 9 0 0 0 0 18h1a2 2 0 0 0 1-4 2 2 0 0 1 1-4h2a4 4 0 0 0 4-4c0-3-4-6-9-6ZM7 8h.01M11 6h.01M16 8h.01M7 13h.01',
  nodeAsset: 'm12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5',
  nodeVoice: 'M8 17V5l11-2v12M8 8l11-2M8 17a3 2 0 1 1-6 0 3 2 0 0 1 6 0Zm11-2a3 2 0 1 1-6 0 3 2 0 0 1 6 0Z',
  nodeNote: 'M4 4h16v11l-5 5H4V4Zm11 16v-5h5M8 9h8m-8 4h4'
} as const

export type StudioIconName = keyof typeof paths

export function StudioIconGlyph({
  name,
  ...props
}: Omit<SVGProps<SVGSVGElement>, 'name'> & { name: StudioIconName }) {
  return (
    <svg
      width="1em"
      height="1em"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      data-studio-icon={name}
      {...props}
    >
      <path d={paths[name]} />
    </svg>
  )
}
