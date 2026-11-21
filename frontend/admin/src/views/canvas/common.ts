import dayjs from 'dayjs';

export function displayText(value: unknown) {
  return value === null || value === undefined || value === '' ? '-' : String(value);
}
export function displayTime(value: unknown) {
  if (!value) return '-';
  const parsed = dayjs(value as string);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : String(value);
}

export function displayBytes(value: unknown) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

export function readPage<T>(response: any): { rows: T[]; total: number } {
  const rows = Array.isArray(response?.data?.items) ? response.data.items : [];
  const total = Number(response?.data?.total ?? rows.length);
  return { rows, total: Number.isFinite(total) ? total : rows.length };
}

export function readData<T>(response: any): T {
  return response?.data as T;
}
