import { useEffect } from 'react';
import { create } from 'zustand';

import {
  getMediaTaskAnomalySummary,
  MediaTaskAnomalySummary
} from '@/api/system/mediaTaskAnomaly';
import { getToken } from '@/utils/auth';

const DEFAULT_REFRESH_SECONDS = 60;

interface MediaTaskAnomalySummaryState {
  summary?: MediaTaskAnomalySummary;
  loading: boolean;
  error?: string;
  refresh: (force?: boolean) => Promise<MediaTaskAnomalySummary>;
}

let subscribers = 0;
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let visibilityListening = false;
let activeToken = getToken();

function clearRefreshTimer() {
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = undefined;
}

function scheduleRefresh(seconds?: number) {
  clearRefreshTimer();
  if (subscribers <= 0 || document.hidden) return;
  const delay = Math.max(10, Number(seconds) || DEFAULT_REFRESH_SECONDS) * 1000;
  refreshTimer = setTimeout(() => {
    useMediaTaskAnomalySummaryStore.getState().refresh(true).catch(() => undefined);
  }, delay);
}

function handleVisibilityChange() {
  if (document.hidden) {
    clearRefreshTimer();
    return;
  }
  if (subscribers > 0) {
    useMediaTaskAnomalySummaryStore.getState().refresh(true).catch(() => undefined);
  }
}

export const useMediaTaskAnomalySummaryStore = create<MediaTaskAnomalySummaryState>((set) => ({
  loading: false,
  refresh: async (force = false) => {
    const requestToken = getToken();
    if (requestToken !== activeToken) {
      activeToken = requestToken;
      set({ summary: undefined, error: undefined });
    }
    set({ loading: true, error: undefined });
    try {
      const summary = await getMediaTaskAnomalySummary(force);
      if (requestToken !== getToken()) return summary;
      set({ summary, loading: false });
      scheduleRefresh(summary.adminRefreshSeconds);
      return summary;
    } catch (error) {
      if (requestToken !== getToken()) throw error;
      set({ loading: false, error: error instanceof Error ? error.message : '异常任务摘要加载失败' });
      scheduleRefresh(DEFAULT_REFRESH_SECONDS);
      throw error;
    }
  }
}));

function subscribeSummary() {
  subscribers++;
  if (subscribers === 1) {
    if (!visibilityListening) {
      document.addEventListener('visibilitychange', handleVisibilityChange);
      visibilityListening = true;
    }
    if (!document.hidden) {
      useMediaTaskAnomalySummaryStore.getState().refresh(false).catch(() => undefined);
    }
  }

  return () => {
    subscribers = Math.max(0, subscribers - 1);
    if (subscribers === 0) {
      clearRefreshTimer();
      if (visibilityListening) {
        document.removeEventListener('visibilitychange', handleVisibilityChange);
        visibilityListening = false;
      }
    }
  };
}

/** 订阅全局唯一的异常任务摘要调度器。 */
export function useMediaTaskAnomalySummary() {
  const state = useMediaTaskAnomalySummaryStore();
  useEffect(() => subscribeSummary(), []);
  return state;
}
