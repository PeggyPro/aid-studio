export interface MediaDimensions {
  width: number;
  height: number;
}

export type MediaKind = 'image' | 'video';

function readImageDimensions(source: File | string): Promise<MediaDimensions> {
  return new Promise((resolve, reject) => {
    const objectUrl = source instanceof File ? URL.createObjectURL(source) : source;
    const image = new Image();
    const cleanup = () => {
      if (source instanceof File) URL.revokeObjectURL(objectUrl);
    };
    image.onload = () => {
      cleanup();
      if (image.naturalWidth > 0 && image.naturalHeight > 0) {
        resolve({ width: image.naturalWidth, height: image.naturalHeight });
      } else {
        reject(new Error('无法读取图片尺寸'));
      }
    };
    image.onerror = () => {
      cleanup();
      reject(new Error('无法读取图片内容'));
    };
    image.src = objectUrl;
  });
}

function readVideoDimensions(source: File | string): Promise<MediaDimensions> {
  return new Promise((resolve, reject) => {
    const objectUrl = source instanceof File ? URL.createObjectURL(source) : source;
    const video = document.createElement('video');
    const cleanup = () => {
      video.onloadedmetadata = null;
      video.onerror = null;
      video.removeAttribute('src');
      video.load();
      if (source instanceof File) URL.revokeObjectURL(objectUrl);
    };
    video.preload = 'metadata';
    video.onloadedmetadata = () => {
      const dimensions = { width: video.videoWidth, height: video.videoHeight };
      cleanup();
      if (dimensions.width > 0 && dimensions.height > 0) {
        resolve(dimensions);
      } else {
        reject(new Error('无法读取视频尺寸'));
      }
    };
    video.onerror = () => {
      cleanup();
      reject(new Error('无法读取视频内容'));
    };
    video.src = objectUrl;
  });
}

export function readMediaDimensions(source: File | string, kind: MediaKind) {
  return kind === 'image' ? readImageDimensions(source) : readVideoDimensions(source);
}
