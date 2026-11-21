import { useEffect, useId, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { Alert, Button, Card, DatePicker, Empty, Segmented, Select, Skeleton, Tag } from 'antd';
import {
  ArrowRightOutlined, CalendarOutlined, CreditCardOutlined, FundOutlined, ReloadOutlined,
  TeamOutlined, WalletOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  getRechargeOverview, invalidateRechargeStats, rechargeChannelLabels,
  type RechargeDay, type RechargeFilter, type RechargeOverview
} from '@/api/aid/payorder';
import './rechargeStats.less';

const DATE_FORMAT = 'YYYY-MM-DD';
const MAX_DAYS = 366;
const beijingDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', dateStyle: 'short' });
const wholeNumber = new Intl.NumberFormat('zh-CN');
const compactNumber = new Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 2 });
const presets = [{ label: '近 7 天', value: 7 }, { label: '近 30 天', value: 30 }, { label: '近 90 天', value: 90 }];

function presetDates(days: number) {
  const today = dayjs(beijingDate.format(new Date()));
  return { startDate: today.subtract(days - 1, 'day').format(DATE_FORMAT), endDate: today.format(DATE_FORMAT) };
}

function money(value: string) {
  const [yuan, cents = '00'] = value.split('.');
  return `¥${yuan.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${cents.padEnd(2, '0')}`;
}

type Props = { active: boolean; onShowOrders: (filter: RechargeFilter) => void };
type Result = { key: string; data?: RechargeOverview; error?: boolean };

export default function RechargeStats({ active, onShowOrders }: Props) {
  const [filter, setFilter] = useState<RechargeFilter>(() => ({ ...presetDates(30), channel: 'all' }));
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<Result>();
  const [rangeError, setRangeError] = useState(false);
  const { startDate, endDate, channel } = filter;
  const key = `${startDate}/${endDate}/${channel}/${revision}`;
  const current = result?.key === key ? result : undefined;
  const data = current?.data;
  const hasOrders = !!data?.orderCount;
  const matchingPreset = presets.find(({ value }) => {
    const dates = presetDates(value);
    return dates.startDate === startDate && dates.endDate === endDate;
  })?.value;

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    getRechargeOverview({ startDate, endDate, channel }).then(({ data }) => {
      if (!cancelled) setResult({ key, data });
    }).catch(() => {
      if (!cancelled) setResult({ key, error: true });
    });
    return () => { cancelled = true; };
  }, [active, startDate, endDate, channel, key]);

  return (
    <section className="recharge-stats" aria-label="充值看板">
      <Card className="recharge-filters" size="small">
        <div className="recharge-filters__row">
          <div className="recharge-filters__group">
            <span className="recharge-filters__label"><CalendarOutlined /> 支付日期</span>
            <Segmented
              aria-label="日期快捷筛选"
              options={matchingPreset ? presets : [...presets, { label: '自定义', value: 0, disabled: true }]}
              value={matchingPreset ?? 0}
              onChange={(days) => {
                setRangeError(false);
                setFilter((previous) => ({ ...previous, ...presetDates(days) }));
              }}
            />
            <DatePicker.RangePicker
              aria-label="自定义支付日期"
              allowClear={false}
              value={[dayjs(startDate), dayjs(endDate)]}
              minDate={dayjs('1970-01-01')}
              maxDate={dayjs(beijingDate.format(new Date()))}
              status={rangeError ? 'error' : undefined}
              onChange={(dates) => {
                if (!dates?.[0] || !dates[1]) return;
                const invalid = dates[1].diff(dates[0], 'day') >= MAX_DAYS;
                setRangeError(invalid);
                if (!invalid) setFilter((previous) => ({
                  ...previous, startDate: dates[0]!.format(DATE_FORMAT), endDate: dates[1]!.format(DATE_FORMAT)
                }));
              }}
            />
          </div>
          <div className="recharge-filters__group">
            <Select<RechargeFilter['channel']>
              aria-label="支付渠道"
              className="recharge-filters__channel"
              value={channel}
              options={Object.entries(rechargeChannelLabels).map(([value, label]) => ({ value, label }))}
              onChange={(value) => setFilter((previous) => ({ ...previous, channel: value }))}
            />
            <Button icon={<ReloadOutlined />} loading={!current} onClick={() => {
              invalidateRechargeStats();
              setRevision((value) => value + 1);
            }}>
              刷新
            </Button>
          </div>
        </div>
        {rangeError && <div className="recharge-filters__error" role="alert">一次最多查看 366 天，请缩短日期范围。</div>}
      </Card>

      {current?.error && <Alert showIcon type="error" message="充值统计加载失败" description="请刷新重试。" />}
      {!current && <Card aria-busy="true"><Skeleton active paragraph={{ rows: 5 }} /></Card>}
      {data && (
        <>
          <div className="recharge-kpis">
            <Card className="recharge-kpi recharge-kpi--primary">
              <div className="recharge-kpi__label"><WalletOutlined /> 充值实付金额</div>
              <strong>{money(data.totalAmount)}</strong>
              <div className="recharge-kpi__hint">所选时段 · 人民币</div>
            </Card>
            <Metric label="成功订单" value={wholeNumber.format(data.orderCount)} hint="按已支付订单计数" icon={<CreditCardOutlined />} />
            <Metric label="充值人数" value={wholeNumber.format(data.payerCount)} hint="按用户去重" icon={<TeamOutlined />} />
            <Metric label="平均单笔" value={money(data.averageAmount)} hint="实付金额 ÷ 成功订单" icon={<FundOutlined />} />
          </div>
          <div className="recharge-panels">
            <Card className="recharge-trend" title="充值走势" extra={<Tag color="success">已支付</Tag>}>
              {hasOrders ? <Trend key={`${startDate}/${endDate}/${channel}`} days={data.trend}
                onShowDay={(date) => onShowOrders({ ...filter, startDate: date, endDate: date })} />
                : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="所选范围内暂无已支付订单" />}
            </Card>
            <Card title="渠道分布" className="recharge-channels">
              <div className="recharge-channels__hint">按实付金额排列</div>
              {hasOrders ? data.channels.map((item) => {
                const total = Number(data.totalAmount);
                const percent = total > 0 ? Number(item.amount) / total * 100 : 0;
                const label = rechargeChannelLabels[item.channel];
                return (
                  <div className="recharge-channel" key={item.channel} data-channel={item.channel}>
                    <div className="recharge-channel__row">
                      <Button type="link" onClick={() => onShowOrders({ ...filter, channel: item.channel })}
                        aria-label={`查看${label}订单`}>{label}<ArrowRightOutlined /></Button>
                      <strong>{money(item.amount)}</strong>
                    </div>
                    <div className="recharge-channel__bar" aria-hidden="true"><span style={{ width: `${percent}%` }} /></div>
                    <div className="recharge-channel__row recharge-channels__hint">
                      <span>{wholeNumber.format(item.orderCount)} 笔</span><span>{percent.toFixed(1)}%</span>
                    </div>
                  </div>
                );
              }) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无渠道数据" />}
            </Card>
          </div>
          <div className="recharge-footer">
            <span>按北京时间的支付日期统计，仅包含当前已支付的订单。</span>
            <Button type="primary" icon={<ArrowRightOutlined />} disabled={!hasOrders}
              onClick={() => onShowOrders(filter)}>查看筛选内订单</Button>
          </div>
        </>
      )}
    </section>
  );
}

function Metric({ label, value, hint, icon }: { label: string; value: string; hint: string; icon: React.ReactNode }) {
  return (
    <Card className="recharge-kpi">
      <div className="recharge-kpi__label">{icon} {label}</div>
      <strong>{value}</strong>
      <div className="recharge-kpi__hint">{hint}</div>
    </Card>
  );
}

const CHART_WIDTH = 720;
const CHART_HEIGHT = 180;

function Trend({ days, onShowDay }: { days: RechargeDay[]; onShowDay: (date: string) => void }) {
  const gradientId = useId();
  const [metric, setMetric] = useState<'amount' | 'orderCount'>('amount');
  const [active, setActive] = useState(() => days.reduce((latest, day, index) => day.orderCount > 0 ? index : latest, 0));
  const selected = days[active];
  const values = days.map((day) => Number(day[metric]));
  const max = Math.max(...values, metric === 'amount' ? 0.01 : 1);
  const step = metric === 'orderCount' ? Math.ceil(max / 4) : Math.ceil(max * 100 / 4) / 100;
  const x = (index: number) => days.length === 1 ? CHART_WIDTH / 2 : index * CHART_WIDTH / (days.length - 1);
  const y = (index: number) => CHART_HEIGHT - values[index] / (step * 4) * CHART_HEIGHT;
  const points = values.map((_, index) => `${x(index)},${y(index)}`).join(' ');
  const labelIndices = [...new Set(Array.from({ length: 5 }, (_, index) => Math.round(index * (days.length - 1) / 4)))];

  const selectByPointer = (event: PointerEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const position = Math.min(1, Math.max(0, (event.clientX - bounds.left) / bounds.width));
    setActive(Math.round(position * (days.length - 1)));
  };
  const selectByKeyboard = (event: KeyboardEvent<SVGSVGElement>) => {
    const destinations: Record<string, number> = {
      ArrowLeft: active - 1, ArrowDown: active - 1, ArrowRight: active + 1, ArrowUp: active + 1,
      Home: 0, End: days.length - 1
    };
    const next = destinations[event.key];
    if (next === undefined) return;
    event.preventDefault();
    setActive(Math.max(0, Math.min(days.length - 1, next)));
  };
  const formatValue = (day: RechargeDay) => metric === 'amount' ? money(day.amount) : `${wholeNumber.format(day.orderCount)} 笔`;

  return (
    <>
      <div className="recharge-trend__controls">
        <div className="recharge-trend__reading">
          <span>{selected.date}</span><strong>{formatValue(selected)}</strong>
        </div>
        <Segmented aria-label="走势指标" value={metric} onChange={setMetric}
          options={[{ label: '实付金额', value: 'amount' }, { label: '订单数', value: 'orderCount' }]} />
      </div>
      <div className="recharge-trend__chart">
        <div className="recharge-trend__axis" aria-hidden="true">
          {[4, 3, 2, 1, 0].map((tick) => <span key={tick}>{compactNumber.format(tick * step)}</span>)}
        </div>
        <div className="recharge-trend__plot">
          <svg viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`} preserveAspectRatio="none"
            role="slider" tabIndex={0} aria-label="选择充值日期" aria-valuemin={1} aria-valuemax={days.length}
            aria-valuenow={active + 1} aria-valuetext={`${selected.date}，${formatValue(selected)}`}
            onPointerMove={selectByPointer} onPointerDown={selectByPointer} onKeyDown={selectByKeyboard}>
            <defs>
              <linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="currentColor" stopOpacity=".24" />
                <stop offset="100%" stopColor="currentColor" stopOpacity=".02" />
              </linearGradient>
            </defs>
            {[0, 1, 2, 3, 4].map((tick) => (
              <line className="recharge-trend__gridline" key={tick} x1="0" x2={CHART_WIDTH}
                y1={tick * CHART_HEIGHT / 4} y2={tick * CHART_HEIGHT / 4} />
            ))}
            {days.length > 1 && <>
              <polygon points={`0,${CHART_HEIGHT} ${points} ${CHART_WIDTH},${CHART_HEIGHT}`} fill={`url(#${gradientId})`} />
              <polyline points={points} fill="none" stroke="currentColor" strokeWidth="2" vectorEffect="non-scaling-stroke" />
            </>}
            <line x1={x(active)} x2={x(active)} y1="0" y2={CHART_HEIGHT}
              stroke="currentColor" strokeDasharray="3 4" vectorEffect="non-scaling-stroke" />
            <line x1={x(active)} x2={x(active)} y1={y(active)} y2={y(active) + 0.1}
              stroke="currentColor" strokeWidth="7" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
          </svg>
          <div className="recharge-trend__dates" aria-hidden="true">
            {labelIndices.map((index) => <span key={index}>{days[index].date.slice(5)}</span>)}
          </div>
        </div>
      </div>
      <div className="recharge-trend__detail">
        <span>{wholeNumber.format(selected.orderCount)} 笔 · {wholeNumber.format(selected.payerCount)} 人</span>
        <Button type="link" disabled={!selected.orderCount} onClick={() => onShowDay(selected.date)}>
          查看当日订单 <ArrowRightOutlined />
        </Button>
      </div>
    </>
  );
}
