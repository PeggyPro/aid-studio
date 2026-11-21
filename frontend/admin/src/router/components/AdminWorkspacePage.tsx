import React from 'react';
import { Tabs } from 'antd';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';

import { resolvePath } from '@/router/utils';
import type { AppRoute } from '@/store/usePermissionStore';
import { useAuth } from '@/hooks/useAuth';
import { adminWorkspaces, authoringKind, authoringView, entityId, getAuthoringAccess, workspaceTabPath } from '@/utils/adminWorkspaces';

function collectPages(routes: AppRoute[], base = ''): Array<{ route: AppRoute; path: string }> {
  return routes.flatMap(route => {
    const path = resolvePath(base, route.path);
    return route.component ? [{ route, path }] : collectPages(route.children ?? [], path);
  });
}

/** Each tab mounts the page admitted by the router for the current role. */
export default function AdminWorkspacePage({ matched, routes }: { matched: AppRoute; routes: AppRoute[] }) {
  const location = useLocation();
  const navigate = useNavigate();
  const { hasPermi } = useAuth();
  const access = getAuthoringAccess(hasPermi);
  const kind = authoringKind(location.search);
  const Page = matched.component!;

  const workspace = adminWorkspaces.find(group => group.tabs.some(tab =>
    tab.view === matched.view && (!tab.authoring || tab.authoring === kind)
  ));
  // The original authoring bookmark still opens the unfiltered editor.
  if (!workspace) return <Page />;

  const pages = collectPages(routes);
  const tabs = workspace.tabs.flatMap((tab, index) => {
    if (tab.authoring && !access[tab.authoring]) return [];
    const route = pages.find(page => page.route.view === tab.view);
    return route ? [{ key: String(index), tab, path: workspaceTabPath(route.path, tab) }] : [];
  });
  const active = tabs.find(({ tab }) => tab.view === matched.view && tab.authoring === kind)
    ?? tabs.find(({ tab }) => tab.view === matched.view && !tab.authoring);

  // Unqualified legacy access keeps the page's existing permission checks.
  if (!active) return <Page scope={matched.view === authoringView ? kind : undefined} />;
  if (active.tab.fallbackOnly) return <Page />;

  const params = new URLSearchParams(location.search);
  const skillId = matched.view === authoringView && kind === 'skill' ? entityId(params.get('skillId')) : undefined;
  const authoringPath = tabs.find(({ tab }) => tab.authoring === 'skill')?.path;
  const rebatePath = tabs.find(({ tab }) => tab.view === 'aid/inviterebate/index')?.path;
  const visibleTabs = tabs.filter(({ tab }) => !tab.fallbackOnly);

  if (workspace.title === 'Skill 管理' && authoringPath) {
    const management = pages.find(page => page.route.view === 'aid/skill/index');
    const Manager = management?.route.component;
    if (management && matched.view === authoringView) {
      params.delete('kind');
      return <Navigate replace to={{ pathname: management.path, search: params.toString() }} />;
    }
    const Editor = pages.find(page => page.route.view === authoringView)?.route.component;
    if (Manager && Editor) return <Manager editor={Editor} initialSkillId={entityId(params.get('skillId'))} />;
  }

  return (
    <>
      {visibleTabs.length > 1 && (
        <Tabs
          style={{ marginBottom: 8 }}
          activeKey={active.key}
          onChange={key => {
            const next = visibleTabs.find(tab => tab.key === key);
            if (next) navigate(next.path);
          }}
          items={visibleTabs.map(({ key, tab }) => ({ key, label: tab.title }))}
        />
      )}
      <Page key={matched.view + ':' + (active.tab.authoring ?? '')} scope={active.tab.authoring} initialSkillId={skillId}
        authoringPath={authoringPath} rebatePath={rebatePath} />
    </>
  );
}
