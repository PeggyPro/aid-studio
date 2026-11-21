import type { AppRoute } from '@/store/usePermissionStore';
import { resolvePath } from './utils';

/** Resolve an old bookmark only to a flow audit page already granted by the server. */
export function resolveLegacyAuditRoute(
  routes: AppRoute[], pathname: string, authenticated: boolean, canList: boolean
): string | undefined {
  if (pathname !== '/comic/comicaudit' || !authenticated || !canList) return undefined;
  const walk = (items: AppRoute[], base = ''): string | undefined => {
    for (const route of items) {
      const full = resolvePath(base, route.path);
      if (route.component && ['aid/audit/index','aid/audit'].includes(route.view || '')
        && full !== pathname && full.startsWith('/') && !full.startsWith('//') && !/[?:#]/.test(full)) return full;
      const child = route.children && walk(route.children, full);
      if (child) return child;
    }
    return undefined;
  };
  return walk(routes);
}
