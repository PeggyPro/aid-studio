import { useEffect, useState } from 'react';
import { auditMediaPath, fetchAuditMedia, type AuditId, type CanvasResource } from '@/api/aid/canvasAudit';

export interface ResourceState {
  urls: Record<string, string>;
  blobs: Record<string, Blob>;
  ready: boolean;
  error: string;
}
export function useAuditResources(
  publicationId: AuditId,
  canvas: Record<string, any> | undefined,
  onReady?: (ready: boolean) => void
): ResourceState {
  const [state, setState] = useState<ResourceState>({ urls: {}, blobs: {}, ready: false, error: '' });
  useEffect(() => {
    let active = true;
    const created: string[] = [];
    setState({ urls: {}, blobs: {}, ready: false, error: '' });
    onReady?.(false);
    const resources = (canvas?.resources || []) as CanvasResource[];
    const map = new Map<string, string>();
    for (const r of resources) {
      map.set(String(r.resourceId), r.resourceUrl || auditMediaPath(publicationId, r.resourceId));
      for (const file of r.bundle?.files || [])
        map.set(String(file.resourceId), file.resourceUrl || auditMediaPath(publicationId, file.resourceId));
    }
    async function load() {
      if (!canvas || !Array.isArray(canvas.objects) || !Array.isArray(canvas.resources) || !canvas.sceneDocument)
        throw new Error('固定快照字段缺失，无法完成内容检查');
      const entries = [...map];
      let cursor = 0;
      const urls: Record<string, string> = {},
        blobs: Record<string, Blob> = {};
      await Promise.all(
        Array.from({ length: Math.min(4, entries.length) }, async () => {
          while (active && cursor < entries.length) {
            const [id, path] = entries[cursor++];
            const blob = await fetchAuditMedia(path);
            if (!active) return;
            const url = URL.createObjectURL(blob);
            created.push(url);
            urls[id] = url;
            blobs[id] = blob;
            const type = resources.find((r) => String(r.resourceId) === id)?.mediaType;
            if (type === 'IMAGE' || blob.type.startsWith('image/')) {
              const image = new Image();
              image.src = url;
              try {
                await image.decode();
              } catch {
                throw new Error(`固定图片资源 ${id} 无法解码`);
              }
            } else if (type === 'VIDEO' || type === 'AUDIO') {
              await new Promise<void>((resolve, reject) => {
                const media = document.createElement(type === 'VIDEO' ? 'video' : 'audio');
                const done = (error?: Error) => {
                  clearTimeout(timer);
                  media.onloadedmetadata = null;
                  media.onerror = null;
                  media.removeAttribute('src');
                  media.load();
                  if (error) reject(error);
                  else resolve();
                };
                const timer = window.setTimeout(() => done(new Error(`固定媒体资源 ${id} 读取超时`)), 30000);
                media.onloadedmetadata = () => done();
                media.onerror = () => done(new Error(`固定媒体资源 ${id} 无法解码`));
                media.preload = 'metadata';
                media.src = url;
              });
            }
          }
        })
      );
      if (active) {
        setState({ urls, blobs, ready: true, error: '' });
        onReady?.(true);
      }
    }
    load().catch((e) => {
      if (active) {
        setState({ urls: {}, blobs: {}, ready: false, error: e instanceof Error ? e.message : '资源加载失败' });
        onReady?.(false);
      }
    });
    return () => {
      active = false;
      created.forEach((url) => URL.revokeObjectURL(url));
      onReady?.(false);
    };
  }, [publicationId, canvas, onReady]);
  return state;
}
