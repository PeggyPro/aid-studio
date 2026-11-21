import React from 'react';
import { Alert, Image, Space, Typography } from 'antd';

import type { RuntimeAgentRun } from '@/api/aid/legacyRuntimePlanAgent';

function isResolvedMediaUrl(value: string) {
  return /^https?:\/\//i.test(value);
}

/** Render only the typed task projection whose @MediaUrl field is resolved by the server. */
export default function RuntimeRunMedia({ run }: { run: RuntimeAgentRun }) {
  const mediaUrl = run.task?.ossUrl;
  if (!mediaUrl) return null;
  const mediaType = run.task?.mediaType || run.outputKind;
  if (!isResolvedMediaUrl(mediaUrl)) {
    return <Alert type="warning" showIcon style={{ marginTop: 18 }} message="媒体地址尚未解析"
      description={<Space direction="vertical" size={4}>
        <Typography.Text>服务端返回的产物仍是对象路径，页面不会猜测或拼接存储域名。</Typography.Text>
        <Typography.Text code copyable>{mediaUrl}</Typography.Text>
      </Space>} />;
  }
  return <Space direction="vertical" size={10} style={{ width: '100%', marginTop: 18 }}>
    <Typography.Title level={5} style={{ margin: 0 }}>媒体产物</Typography.Title>
    {mediaType === 'IMAGE' && <Image src={mediaUrl} alt="运行产物" style={{ maxWidth: '100%', maxHeight: 460 }} />}
    {mediaType === 'VIDEO' && <video src={mediaUrl} controls preload="metadata"
      style={{ display: 'block', width: '100%', maxHeight: 520, background: '#0f172a' }} />}
    {mediaType === 'AUDIO' && <audio src={mediaUrl} controls preload="metadata" style={{ width: '100%' }} />}
    <Typography.Link href={mediaUrl} target="_blank" rel="noreferrer">在新窗口打开产物</Typography.Link>
  </Space>;
}
