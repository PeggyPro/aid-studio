type RecordValue = Record<string, unknown>;
export const recordValue = (value: unknown): RecordValue =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as RecordValue : {};

export function accountNumber(value: unknown): number | undefined {
  if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim() === '') return undefined;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

const services: Record<string, string> = {
  text_to_music: '文本生成音乐', video_to_music: '视频生成音乐',
  video_to_video_music: '视频配乐并输出视频', text_to_sfx: '文本生成音效',
  video_to_sfx: '视频生成音效', video_to_video_sfx: '视频音效并输出视频',
  audio_ducking: '人声与背景音乐混音', video_to_sound: '视频音乐与音效',
  video_to_video_sound: '综合配乐并输出视频', video_analysis: '视频分析',
  dubbing: '视频配音', proofread: '字幕校对'
};

export function serviceTrialRows(details: unknown) {
  const source = recordValue(recordValue(details).services);
  const trials = recordValue(source.trial);
  const enabled = new Set(Array.isArray(source.available_services)
    ? source.available_services.filter((item): item is string => typeof item === 'string') : []);
  return Array.from(new Set([...enabled, ...Object.keys(trials)])).map((code) => {
    const trial = recordValue(trials[code]);
    return { code, label: services[code] || code, available: enabled.has(code),
      granted: accountNumber(trial.granted), used: accountNumber(trial.used), remaining: accountNumber(trial.remaining) };
  });
}

export function accountDate(value: unknown, seconds = false): string {
  if (typeof value !== 'number' && typeof value !== 'string') return '未知';
  const date = new Date(seconds ? Number(value) * 1000 : value);
  return Number.isFinite(date.valueOf()) ? new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(date) : '未知';
}

export function accountDetailRows(details: unknown) {
  const root = recordValue(details), service = recordValue(root.services);
  const rows: { key: string; label: string; value: string | number }[] = [];
  const add = (key: string, label: string, value: unknown, suffix = '') => {
    const number = accountNumber(value);
    if (number != null) rows.push({ key, label, value: `${number.toLocaleString('zh-CN', { maximumFractionDigits: 8 })}${suffix}` });
  };
  add('recharge', '累计充值', root.total_recharge, ' CNY');
  add('spending', '累计消费', root.total_spending, ' CNY');
  add('concurrency', '并发上限', root.concurrent_request_limit ?? service.concurrency_limit);
  add('rpm', '每分钟请求上限', service.rpm_limit);
  add('upload', '单文件上传上限', service.max_upload_size_mb, ' MB');
  add('discount', '账户计费系数', service.discount_factor, ' 倍');
  const tiers: Record<string, string> = { free: '免费', starter: '入门', creator: '创作者', pro: '专业', scale: '规模', business: '商业', enterprise: '企业' };
  const statuses: Record<string, string> = { free: '免费账户', active: '有效', canceled: '已取消', past_due: '待付款' };
  if (typeof root.tier === 'string') rows.push({ key: 'tier', label: '账户套餐', value: tiers[root.tier] || root.tier });
  if (typeof root.status === 'string') rows.push({ key: 'status', label: '账户状态', value: statuses[root.status] || root.status });
  if (root.next_character_count_reset_unix != null) rows.push({ key: 'reset', label: '字符额度下次重置', value: accountDate(root.next_character_count_reset_unix, true) });
  return rows;
}

export function accountUsage(details: unknown) {
  const usage = recordValue(recordValue(details).usage), summary = recordValue(usage.summary);
  return { summary, daily: (Array.isArray(usage.daily) ? usage.daily : []).map(recordValue) };
}
