import type { SmartEditAudioClip, SmartEditSubtitleCue, SmartEditTextItem, SmartEditTimeline, SmartEditTrack, SmartEditVideoClip } from '@/types/studio'
import { clipEndSeconds, smartEditMediaClips, timelineDurationSeconds } from './studioSmartEdit'
import { validateSmartEditExport } from './smartEditExport'

const xml = (value: string) => value.replace(/[\x00-\x08\x0b\x0c\x0e-\x1f]/g, '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;')
const comment = (value: string) => `<!-- ${xml(value).replace(/--/g, '—')} -->`
export const smartEditFcpxmlTime = (seconds: number, fps = 30) => `${Math.max(0, Math.round(seconds * fps))}/${fps}s`

export function buildSmartEditFcpxml(timeline: SmartEditTimeline, options: { projectName: string; width: number; height: number; fps?: number; hiddenTracks?: SmartEditTrack[]; baseUrl?: string }) {
  validateSmartEditExport(timeline)
  const fps = options.fps || 30
  const time = (seconds: number) => smartEditFcpxmlTime(seconds, fps)
  const hidden = options.hiddenTracks || []
  const resources: string[] = [`<format id="r1" frameDuration="1/${fps}s" width="${options.width}" height="${options.height}" colorSpace="1-1-1 (Rec. 709)"/>`, '<effect id="titleEffect" name="Basic Title" uid=".../Titles.localized/Bumper:Opener.localized/Basic Title.localized/Basic Title.moti"/>']
  const ids = new Map<string, string>()
  const notes: string[] = [comment('源媒体不随工程打包；下载 URL 可能过期，导入后请重新链接本地媒体。字幕转换为可编辑标题。')]
  for (const clip of smartEditMediaClips(timeline)) {
    const sourceUrl = /^\.?\.?\//.test(clip.sourceUrl) && options.baseUrl ? new URL(clip.sourceUrl, options.baseUrl).href : clip.sourceUrl
    if (!/^https?:\/\//i.test(sourceUrl)) throw new Error('FCPXML 需要可下载的 http(s) 素材地址，请先将素材保存到画布资源')
    const id = `asset${ids.size + 1}`
    ids.set(clip.id, id)
    const video = 'track' in clip
    resources.push(`<asset id="${id}" name="${xml(clip.sourceNodeId)}" start="0s" duration="${time(clip.sourceDurationSeconds)}"${video ? ' hasVideo="1" format="r1"' : ''} hasAudio="1" audioSources="1" audioChannels="2" audioRate="48000"><media-rep kind="original-media" src="${xml(sourceUrl)}"/></asset>`)
  }
  const media = (clip: SmartEditAudioClip, lane: number, enabled = true) => {
    const video = 'track' in clip ? clip as SmartEditVideoClip : null
    const rect = video?.overlayRect
    const adjust = rect ? `<adjust-transform position="${((rect.x + rect.w / 2 - 0.5) * options.width / options.height * 100).toFixed(5)} ${((0.5 - rect.y - rect.h / 2) * 100).toFixed(5)}" scale="${rect.w} ${rect.h}"/>` : ''
    if (video?.transitionOut && video.transitionOut.type !== 'cut') notes.push(comment(`unsupported-transition ${clip.id}: ${video.transitionOut.type} (${video.transitionOut.durationSeconds}s) → cut；请在 Resolve 重建转场，保留精确入出点。`))
    const db = clip.volume > 0 ? `${(20 * Math.log10(clip.volume)).toFixed(3)}dB` : '-96dB'
    return `<asset-clip ref="${ids.get(clip.id)}" name="${xml(clip.id)}"${lane ? ` lane="${lane}"` : ''} offset="${time(clip.timelineStartSeconds)}" start="${time(clip.range.startSeconds)}" duration="${time(clip.range.endSeconds - clip.range.startSeconds)}" enabled="${enabled ? 1 : 0}"${video ? ' format="r1"' : ' audioRole="dialogue"'}>${adjust}<adjust-volume amount="${db}"/></asset-clip>`
  }
  const main = [...timeline.videoClips].sort((a, b) => a.timelineStartSeconds - b.timelineStartSeconds)
  const mainSpine: string[] = []
  let end = 0
  const overlap = main.some((clip, index) => index > 0 && clip.timelineStartSeconds < clipEndSeconds(main[index - 1]) - 0.001)
  const connected: string[] = []
  if (overlap) {
    notes.push(comment('主轨存在重叠，映射为独立连接视频轨以保留所有片段；请核对导入后的叠放顺序。'))
    timeline.videoClips.forEach((clip, index) => connected.push(media(clip, index + 1, !hidden.includes('video'))))
  } else {
    for (const clip of main) {
      if (clip.timelineStartSeconds > end) mainSpine.push(`<gap name="黑场" offset="${time(end)}" start="0s" duration="${time(clip.timelineStartSeconds - end)}"/>`)
      mainSpine.push(media(clip, 0, !hidden.includes('video'))); end = clipEndSeconds(clip)
    }
    if (mainSpine.length) connected.push(`<spine lane="1" offset="0s">${mainSpine.join('')}</spine>`)
  }
  const overlayLane = Math.max(2, main.length + 1)
  timeline.overlayClips.forEach((clip, index) => connected.push(media(clip, overlayLane + index, !hidden.includes('overlay'))))
  timeline.audioClips.forEach((clip, index) => connected.push(media(clip, -index - 1, !hidden.includes('audio'))))
  const title = (item: SmartEditSubtitleCue | SmartEditTextItem, index: number, subtitle: boolean) => {
    const styleId = `textStyle${index}`
    const isText = 'fontSize' in item
    const color = isText && /^#[0-9a-f]{6}$/i.test(item.color) ? [1, 3, 5].map((i) => parseInt(item.color.slice(i, i + 2), 16) / 255).join(' ') + ' 1' : '1 1 1 1'
    if (isText) notes.push(comment(`title-layout ${item.id}: rect=${JSON.stringify(item.rect)}；Resolve 中核对字体、换行和位置。${item.animation !== 'none' ? ` unsupported-animation=${item.animation}，需手工重建。` : ''}`))
    const position = isText ? `${((item.rect.x + item.rect.w / 2 - 0.5) * options.width / options.height * 100).toFixed(5)} ${((0.5 - item.rect.y - item.rect.h / 2) * 100).toFixed(5)}` : '0 -36'
    return `<title ref="titleEffect" lane="${overlayLane + timeline.overlayClips.length + index}" name="${xml(item.id)}" offset="${time(item.timelineStartSeconds)}" start="0s" duration="${time(item.durationSeconds)}" enabled="${hidden.includes(subtitle ? 'subtitle' : 'text') ? 0 : 1}"><text><text-style ref="${styleId}">${xml(item.text)}</text-style></text><text-style-def id="${styleId}"><text-style font="Arial" fontSize="${isText ? item.fontSize : 40}" fontColor="${color}" alignment="center"/></text-style-def><adjust-transform position="${position}"/></title>`
  }
  timeline.subtitles.forEach((cue, index) => connected.push(title(cue, index + 1, true)))
  timeline.texts.forEach((item, index) => connected.push(title(item, timeline.subtitles.length + index + 1, false)))
  const duration = time(timelineDurationSeconds(timeline))
  return `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE fcpxml>\n<fcpxml version="1.9"><resources>${resources.join('')}</resources><library><event name="AID Studio"><project name="${xml(options.projectName)}"><sequence format="r1" duration="${duration}" tcStart="0s" tcFormat="NDF" audioLayout="stereo" audioRate="48k"><spine><gap name="剪辑成片" offset="0s" start="0s" duration="${duration}">${connected.join('')}</gap></spine></sequence></project></event></library>${notes.join('\n')}</fcpxml>`
}
