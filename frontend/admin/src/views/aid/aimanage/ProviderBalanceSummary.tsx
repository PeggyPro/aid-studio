import { Alert, Card, Col, Descriptions, Empty, Row, Statistic, Table, Tag, Typography } from 'antd';
import { accountDate, accountDetailRows, accountNumber, accountUsage, serviceTrialRows } from './providerAccountSummary';

export interface ProviderBalanceData {
  balance?: number | string;
  unit?: string;
  queriedAt?: number;
  isAvailable?: boolean;
  balanceAvailable?: boolean;
  delayNotice?: string;
  balanceInfos?: { currency: string; total_balance: string; granted_balance: string; topped_up_balance: string }[];
  remains?: { type: string; credit_remain: number | string; concurrency_limit?: number; current_concurrency?: number }[];
  resource_pack_subscribe_infos?: Record<string, any>[];
  credits?: number;
  creditsUsed?: number;
  accountDetails?: Record<string, unknown>;
  balanceKind?: string;
}

export function balanceNumber(value: unknown): string {
  if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim() === '') return '未知';
  const number = Number(value);
  return Number.isFinite(number) ? number.toLocaleString('zh-CN', { maximumFractionDigits: 8 }) : '金额格式异常';
}

const packageNames: Record<string, string> = { test: '测试资源包', metered: '积分资源包', concurrent: '并发资源包' };

export default function ProviderBalanceSummary({ data }: { data: ProviderBalanceData | null }) {
  if (!data) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="查询后展示官方账户余额" />;
  const currencies = data.balanceInfos;
  const characters = data.balanceKind === 'characters' || data.unit === 'characters';
  const knownBalance = data.balanceAvailable !== false && accountNumber(data.balance) != null;
  const unit = characters ? '字符' : data.unit;
  const trials = serviceTrialRows(data.accountDetails);
  const detailRows = accountDetailRows(data.accountDetails);
  const { summary, daily } = accountUsage(data.accountDetails);
  return <>
    {!currencies?.length && !knownBalance && <Alert type="info" showIcon
      message={characters ? '本次未取得剩余字符额度' : '官方接口本次未提供现金余额'}
      description={characters ? '已返回的字符总额度、已用额度分别展示；缺失值保持未知。'
        : '无法据此判断现金余额为零。下方展示官方已返回的试用次数和账户用量；现金余额请在供应商控制台查看。'}
      style={{ marginBottom: 12 }} />}
    {data.isAvailable === false && <Alert type="warning" showIcon message="官方标记账户余额不足以调用，请前往供应商检查余额。" style={{ marginBottom: 12 }} />}
    {currencies?.length ? <Row gutter={[12, 12]}>
      {currencies.map((row) => <Col xs={24} md={12} key={row.currency}>
        <Card size="small"><Statistic title={`可用余额 · ${row.currency}`} value={0} formatter={() => balanceNumber(row.total_balance)} />
          <Descriptions size="small" column={1} style={{ marginTop: 12 }} items={[
            { key: 'grant', label: '赠送余额', children: `${balanceNumber(row.granted_balance)} ${row.currency}` },
            { key: 'paid', label: '充值余额', children: `${balanceNumber(row.topped_up_balance)} ${row.currency}` }
          ]} />
        </Card>
      </Col>)}
    </Row> : (knownBalance || data.credits != null || data.creditsUsed != null) && <Card size="small">
      {knownBalance && <Statistic title={characters ? '剩余字符额度' : data.unit?.toLowerCase() === 'credits' ? '可用积分' : '可用余额'} value={0}
        formatter={() => balanceNumber(data.balance)} suffix={unit || undefined} />}
      {(data.credits != null || data.creditsUsed != null) && <Descriptions size="small" column={2} style={{ marginTop: 12 }} items={[
        { key: 'credits', label: characters ? '本期字符总额度' : '累计额度', children: `${balanceNumber(data.credits)} ${unit || ''}` },
        { key: 'used', label: characters ? '本期已用字符' : '累计消耗', children: `${balanceNumber(data.creditsUsed)} ${unit || ''}` }
      ]} />}
    </Card>}
    {detailRows.length > 0 && <Card size="small" title="账户信息" style={{ marginTop: 16 }}>
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={detailRows.map((row) => ({ key: row.key, label: row.label, children: row.value }))} />
    </Card>}
    {trials.length > 0 && <Card size="small" title="逐服务试用额度" style={{ marginTop: 16 }}>
      <Table size="small" rowKey="code" pagination={false} dataSource={trials} scroll={{ x: 560 }} columns={[
        { title: '服务', dataIndex: 'label' },
        { title: '访问权限', dataIndex: 'available', render: (value: boolean) => <Tag color={value ? 'green' : 'default'}>{value ? '可访问' : '未返回访问权限'}</Tag> },
        { title: '赠送次数', dataIndex: 'granted', render: balanceNumber },
        { title: '已用次数', dataIndex: 'used', render: balanceNumber },
        { title: '剩余次数', dataIndex: 'remaining', render: balanceNumber }
      ]} />
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 8 }}>各服务额度独立；有试用次数不代表任意时长、多结果或付费选项均可免费调用。</Typography.Paragraph>
    </Card>}
    {Object.keys(summary).length > 0 && <Card size="small" title="账户用量统计" style={{ marginTop: 16 }}>
      <Descriptions size="small" column={2} items={[
        { key: 'requests', label: '请求次数', children: balanceNumber(summary.total_requests) },
        { key: 'duration', label: '累计输出时长', children: `${balanceNumber(summary.total_duration_seconds)} 秒` },
        { key: 'cost', label: '累计消费', children: `${balanceNumber(summary.total_cost)} USD` },
        { key: 'period', label: '统计区间', children: `${accountDate(summary.period_start)} 至 ${accountDate(summary.period_end)}` }
      ]} />
    </Card>}
    {daily.length > 0 && <Table size="small" style={{ marginTop: 16 }} pagination={false} rowKey={(row) => String(row.date)}
      dataSource={daily} columns={[
        { title: '日期', dataIndex: 'date', render: (value: unknown) => typeof value === 'string' ? value : '未知' },
        { title: '请求次数', dataIndex: 'requests', render: balanceNumber },
        { title: '输出时长（秒）', dataIndex: 'duration_seconds', render: balanceNumber },
        { title: '消费（USD）', dataIndex: 'cost', render: balanceNumber }
      ]} />}
    {Array.isArray(data.remains) && <Table size="small" style={{ marginTop: 16 }} pagination={false}
      dataSource={data.remains} rowKey="type" locale={{ emptyText: '官方未返回积分资源包明细' }} columns={[
        { title: '资源包类型', dataIndex: 'type', render: (value: string) => packageNames[value] || value },
        { title: '剩余积分', dataIndex: 'credit_remain', render: balanceNumber },
        { title: '并发上限', dataIndex: 'concurrency_limit', render: (value: unknown) => value == null ? '—' : balanceNumber(value) },
        { title: '使用中并发', dataIndex: 'current_concurrency', render: (value: unknown) => value == null ? '—' : balanceNumber(value) }
      ]} />}
    <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
      数据来自供应商账户；积分不等于人民币，不同币种不合并计算。本系统的用户积分不受影响。
    </Typography.Paragraph>
  </>;
}
