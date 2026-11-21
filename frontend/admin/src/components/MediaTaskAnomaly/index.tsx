import React from 'react';
import { Badge, Button, Card, Col, Row, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ReloadOutlined,
  WarningOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router-dom';

import {
  MEDIA_TASK_ANOMALY_ROUTE,
  MediaTaskAnomalyLatest
} from '@/api/system/mediaTaskAnomaly';
import { useMediaTaskAnomalySummary } from '@/store/useMediaTaskAnomalySummaryStore';
import './style.less';

const { Text } = Typography;

export function formatWaitingTime(seconds?: number | null) {
  if (seconds === null || seconds === undefined) return '-';
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  if (total < 60) return `${total} 秒`;
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days > 0) return `${days} 天 ${hours} 小时`;
  if (hours > 0) return `${hours} 小时 ${minutes} 分钟`;
  return `${minutes} 分钟`;
}

function latestText(latest?: MediaTaskAnomalyLatest | null) {
  if (!latest) return '暂无待处理异常';
  const pieces = [`任务 ${latest.taskId}`];
  if (latest.mediaType) pieces.push(latest.mediaType);
  if (latest.anomalyType) pieces.push(latest.anomalyType);
  return pieces.join(' · ');
}

export function MediaTaskAnomalyHeaderEntry() {
  const navigate = useNavigate();
  const { summary, loading, error } = useMediaTaskAnomalySummary();
  const count = summary?.pendingCount || 0;

  return (
    <Tooltip title={error ? '异常任务摘要暂不可用' : count > 0 ? `有 ${count} 项异常任务待处理` : '异常任务'}>
      <Badge count={count} overflowCount={99} size="small" offset={[-2, 2]}>
        <Button
          type="text"
          className={`media-task-anomaly-entry${count > 0 ? ' is-pending' : ''}`}
          icon={<WarningOutlined spin={loading && !summary} />}
          aria-label={count > 0 ? `异常任务，${count} 项待处理` : '异常任务'}
          onClick={() => navigate(MEDIA_TASK_ANOMALY_ROUTE)}
        >
          <span className="media-task-anomaly-entry__label">异常任务</span>
        </Button>
      </Badge>
    </Tooltip>
  );
}

export function MediaTaskAnomalyDashboardCard() {
  const navigate = useNavigate();
  const { summary, loading, error } = useMediaTaskAnomalySummary();
  const count = summary?.pendingCount || 0;
  const latest = summary?.latestAnomaly;

  return (
    <Spin spinning={loading && !summary}>
      <Card className={`media-task-anomaly-card${count > 0 ? ' is-pending' : ''}`} bordered={false}>
        <div className="media-task-anomaly-card__head">
          <Space size={9}>
            <span className="media-task-anomaly-card__icon">
              {count > 0 ? <WarningOutlined /> : <CheckCircleOutlined />}
            </span>
            <div>
              <strong>待处理异常任务</strong>
              <div className="media-task-anomaly-card__hint">
                {error ? '摘要暂不可用，将自动重试' : `每 ${summary?.adminRefreshSeconds || 60} 秒自动刷新`}
              </div>
            </div>
          </Space>
          <Button type="link" icon={<ArrowRightOutlined />} onClick={() => navigate(MEDIA_TASK_ANOMALY_ROUTE)}>
            进入处理
          </Button>
        </div>
        <Row gutter={[24, 14]} align="middle">
          <Col xs={24} sm={8} lg={6}>
            <div className="media-task-anomaly-card__metric">
              <span>待处理</span>
              <b className={count > 0 ? 'is-warning' : ''}>{count.toLocaleString('zh-CN')}</b>
              <small>项</small>
            </div>
          </Col>
          <Col xs={24} sm={8} lg={6}>
            <div className="media-task-anomaly-card__metric">
              <span>最长等待</span>
              <b>{formatWaitingTime(summary?.longestWaitingSeconds)}</b>
            </div>
          </Col>
          <Col xs={24} sm={8} lg={12}>
            <div className="media-task-anomaly-card__latest">
              <span>最近异常</span>
              <Tooltip title={latestText(latest)}>
                <Text ellipsis>{latestText(latest)}</Text>
              </Tooltip>
              <small>{latest?.detectedAt ? dayjs(latest.detectedAt).format('YYYY-MM-DD HH:mm:ss') : '-'}</small>
            </div>
          </Col>
        </Row>
      </Card>
    </Spin>
  );
}

export function MediaTaskAnomalyPageSummary() {
  const { summary, loading, error, refresh } = useMediaTaskAnomalySummary();
  const count = summary?.pendingCount || 0;
  return (
    <div className="media-task-anomaly-page-summary">
      <div className="media-task-anomaly-page-summary__item">
        <span>待处理数量</span>
        <b className={count > 0 ? 'is-warning' : ''}>{count.toLocaleString('zh-CN')}</b>
      </div>
      <div className="media-task-anomaly-page-summary__divider" />
      <div className="media-task-anomaly-page-summary__item">
        <span>最长等待</span>
        <b>{formatWaitingTime(summary?.longestWaitingSeconds)}</b>
      </div>
      <div className="media-task-anomaly-page-summary__divider" />
      <div className="media-task-anomaly-page-summary__recent">
        <span>最近异常</span>
        <Text ellipsis>{latestText(summary?.latestAnomaly)}</Text>
      </div>
      <Tag icon={error ? <WarningOutlined /> : <ClockCircleOutlined />} color={error ? 'error' : 'default'}>
        {error ? '摘要加载失败' : `${summary?.adminRefreshSeconds || 60} 秒刷新`}
      </Tag>
      <Button size="small" icon={<ReloadOutlined spin={loading} />} onClick={() => refresh(true).catch(() => undefined)}>
        刷新摘要
      </Button>
    </div>
  );
}
