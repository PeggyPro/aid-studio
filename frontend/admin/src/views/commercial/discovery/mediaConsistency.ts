export interface PreviewVideoReconcileResult {
  previewVideoUrl: string | null;
  cleared: boolean;
}

/** 主视频真实替换或移除时清空旧预览；普通回填或未变更时保持原值。 */
export function reconcilePreviewVideoUrl(
  previewVideoUrl: string | null | undefined,
  mainVideoChanged: boolean
): PreviewVideoReconcileResult {
  const normalized = previewVideoUrl?.trim() || null;
  if (!mainVideoChanged || !normalized) {
    return { previewVideoUrl: normalized, cleared: false };
  }
  return { previewVideoUrl: null, cleared: true };
}
