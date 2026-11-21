import { assistantErrorMessage } from '@/utils/assistant/assistantRequestCoordinator';

const storageMessages: Readonly<Record<string, string>> = {
  DOCUMENT_HISTORY_LIMIT: '场景历史版本已达到额度。请联系管理员调整工作区的历史版本额度后，再重试保存；已有版本不会删除。',
  DOCUMENT_QUOTA_EXCEEDED: '场景文档存储空间已达到额度。请联系管理员调整工作区容量后，再重试保存；已有版本不会删除。',
  WORKSPACE_STORAGE_QUOTA_EXCEEDED: '工作区文件存储空间已达到额度。请联系管理员调整工作区容量后再重试；已有文件不会删除。',
  WORKSPACE_STORAGE_COUNT_EXCEEDED: '工作区历史文件数量已达到额度。请联系管理员调整历史文件额度后再重试；已有文件不会删除。',
};

/** Presentation only; keep the original failure, quota enforcement and retry decision unchanged. */
export function previzErrorMessage(error: unknown): string {
  const message = assistantErrorMessage(error);
  return Object.prototype.hasOwnProperty.call(storageMessages, message) ? storageMessages[message] : message;
}
