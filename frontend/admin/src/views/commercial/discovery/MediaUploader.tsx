import React, { useMemo, useState } from 'react';
import { Button, Progress, Space, Upload, message } from 'antd';
import {
  DeleteOutlined,
  FileImageOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  UploadOutlined
} from '@ant-design/icons';
import type { UploadProps } from 'antd';

import { request } from '@/utils/request';
import type { UploadLimits } from '@/utils/uploadLimits';
import { readMediaDimensions, type MediaDimensions, type MediaKind } from './mediaMetadata';
import {
  isDiscoveryUploadTypeDisabled,
  resolveDiscoveryUploadRule,
  validateDiscoveryUploadCandidate
} from './uploadRule';

interface Props {
  kind: MediaKind;
  value?: string | null;
  limits: UploadLimits | null;
  limitsLoading?: boolean;
  disabled?: boolean;
  onChange?: (value: string) => void;
  /** 仅在用户完成真实上传或主动移除时触发，不受表单回填影响。 */
  onFileChanged?: (value: string) => void;
  onMetadata?: (dimensions?: MediaDimensions) => void;
  onUploadingChange?: (uploading: boolean) => void;
}

function extractUploadUrl(response: any): string | undefined {
  return response?.url || response?.data?.url || response?.fileName || response?.data?.fileName;
}

export default function MediaUploader({
  kind,
  value,
  limits,
  limitsLoading,
  disabled,
  onChange,
  onFileChanged,
  onMetadata,
  onUploadingChange
}: Props) {
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState('');
  const rule = useMemo(() => resolveDiscoveryUploadRule(limits, kind), [limits, kind]);
  const accept = rule.configured ? rule.extensions.map((extension) => `.${extension}`).join(',') : `${kind}/*`;
  const kindText = kind === 'image' ? '图片' : '视频';
  const typeDisabled = isDiscoveryUploadTypeDisabled(rule);

  const beforeUpload: UploadProps['beforeUpload'] = (file) => {
    setError('');
    const validationError = validateDiscoveryUploadCandidate(rule, kind, file);
    if (!validationError) return true;
    const text =
      validationError === 'mime'
        ? `所选文件不是有效的${kindText}类型`
        : validationError === 'size'
          ? `${kindText}大小不能超过 ${rule.maxSizeMb}MB`
          : validationError === 'unavailable' || rule.extensions.length === 0
            ? `当前存储配置未开放${kindText}上传`
            : `仅支持 ${rule.extensions.map((item) => item.toUpperCase()).join('、')} 格式`;
    message.error(text);
    setError(text);
    return Upload.LIST_IGNORE;
  };

  const customRequest: UploadProps['customRequest'] = async (options) => {
    const file = options.file as File;
    setUploading(true);
    setProgress(0);
    setError('');
    onUploadingChange?.(true);
    try {
      const dimensions = await readMediaDimensions(file, kind);
      const formData = new FormData();
      formData.append('file', file);
      const response = await request({
        url: '/common/upload',
        method: 'post',
        data: formData,
        // FormData 序列化后均为 "{}"，需关闭 JSON 防重复提交，避免两个上传控件互相误拦。
        headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false } as any,
        onUploadProgress: (event) => {
          if (!event.total) return;
          const next = Math.round((event.loaded / event.total) * 100);
          setProgress(next);
          options.onProgress?.({ percent: next });
        }
      });
      const url = extractUploadUrl(response);
      if (!url) throw new Error('上传成功，但未返回文件地址');
      onChange?.(url);
      onFileChanged?.(url);
      onMetadata?.(dimensions);
      options.onSuccess?.(response);
      setProgress(100);
      message.success(`${kindText}上传成功`);
    } catch (uploadError: any) {
      const isTooLarge = uploadError?.response?.status === 413;
      const text = isTooLarge
        ? '文件被网关拒绝（413），请联系管理员检查上传网关限制'
        : uploadError?.message || `${kindText}上传失败，请重试`;
      setError(text);
      if (isTooLarge) message.error(text);
      options.onError?.(uploadError instanceof Error ? uploadError : new Error(text));
    } finally {
      setUploading(false);
      onUploadingChange?.(false);
    }
  };

  const remove = () => {
    onChange?.('');
    onFileChanged?.('');
    onMetadata?.(undefined);
    setError('');
    setProgress(0);
  };

  return (
    <div className="discovery-uploader">
      {value ? (
        <div className="discovery-uploader__preview">
          {kind === 'image' ? (
            <img src={value} alt="已上传图片预览" loading="lazy" />
          ) : (
            <video src={value} controls controlsList="nodownload" preload="metadata" />
          )}
          <div className="discovery-uploader__preview-actions">
            <Upload
              accept={accept || undefined}
              beforeUpload={beforeUpload}
              customRequest={customRequest}
              disabled={disabled || uploading || typeDisabled}
              maxCount={1}
              showUploadList={false}
            >
              <Button icon={<ReloadOutlined />} disabled={disabled || uploading || typeDisabled}>
                替换
              </Button>
            </Upload>
            <Button danger icon={<DeleteOutlined />} disabled={disabled || uploading} onClick={remove}>
              移除
            </Button>
          </div>
        </div>
      ) : (
        <Upload.Dragger
          accept={accept || undefined}
          beforeUpload={beforeUpload}
          customRequest={customRequest}
          disabled={disabled || uploading || typeDisabled}
          maxCount={1}
          showUploadList={false}
          className="discovery-uploader__dragger"
        >
          <div className="discovery-uploader__icon">
            {kind === 'image' ? <FileImageOutlined /> : <PlayCircleOutlined />}
          </div>
          <div className="discovery-uploader__title">点击或拖拽上传{kindText}</div>
          <div className="discovery-uploader__hint">
            {rule.configured
              ? rule.mode === 'global'
                ? '使用服务器全局规则校验，并读取真实宽高'
                : '上传前会校验格式、大小并读取真实宽高'
              : '将读取媒体信息，格式与大小由服务器校验'}
          </div>
        </Upload.Dragger>
      )}

      {uploading && (
        <div className="discovery-uploader__progress" aria-live="polite">
          <Progress percent={progress} size="small" status="active" />
        </div>
      )}
      <Space size={6} wrap className="discovery-uploader__rule">
        <UploadOutlined />
        <span>
          {limitsLoading
            ? '正在读取上传限制…'
            : !rule.configured
              ? '上传规则暂未读取，由服务器校验'
              : rule.extensions.length > 0
                ? `${rule.mode === 'global' ? '全局规则：' : ''}支持 ${rule.extensions.map((item) => item.toUpperCase()).join('、')}${rule.maxSizeMb ? `，单文件不超过 ${rule.maxSizeMb}MB` : ''}`
                : `当前存储配置未开放${kindText}格式`}
        </span>
      </Space>
      {error && (
        <div className="discovery-uploader__error" role="alert">
          {error}
        </div>
      )}
    </div>
  );
}
