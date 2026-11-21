export const authoringView = 'aid/authoring/index';

type AuthoringKind = 'skill' | 'agent';
export type AuthoringAccess = Record<AuthoringKind, boolean>;

export interface WorkspaceTab {
  view: string;
  title: string;
  authoring?: AuthoringKind;
  fallbackOnly?: true;
}

export interface AdminWorkspace {
  title: string;
  tabs: WorkspaceTab[];
}

export const adminWorkspaces: AdminWorkspace[] = [
  { title: 'Skill 管理', tabs: [
    { view: 'aid/skill/index', title: 'Skill 列表' },
    { view: authoringView, title: 'Skill 编制', authoring: 'skill' }
  ] },
  { title: 'Agent 管理', tabs: [
    { view: 'aid/runtimeAgent/index', title: '旧版 Agent', fallbackOnly: true },
    { view: authoringView, title: '助手 Agent', authoring: 'agent' }
  ] },
  { title: '音色管理', tabs: [
    { view: 'aid/voicelibrary/index', title: '音色库' },
    { view: 'aid/voicetag/index', title: '标签管理' }
  ] },
  { title: '错误规则管理', tabs: [
    { view: 'aid/errorrule/index', title: '识别规则' },
    { view: 'aid/errorlog/index', title: '未识别样本' }
  ] },
  { title: '邀请与返佣', tabs: [
    { view: 'aid/inviterelation/index', title: '邀请关系' },
    { view: 'aid/inviterebate/index', title: '返佣流水' }
  ] }
];

export function getAuthoringAccess(hasPermission: (permission: string) => boolean): AuthoringAccess {
  return {
    skill: hasPermission('aid:skill:list') || hasPermission('aid:skill:query') || hasPermission('aid:skill:edit'),
    agent: hasPermission('aid:assistant:agent:list') || hasPermission('aid:assistant:agent:query') ||
      hasPermission('aid:assistant:agent:edit')
  };
}

export function authoringKind(search: string): AuthoringKind | undefined {
  const kind = new URLSearchParams(search).get('kind');
  return kind === 'skill' || kind === 'agent' ? kind : undefined;
}

export function authoringTitle(kind?: AuthoringKind) {
  return kind === 'agent' ? '助手 Agent' : kind === 'skill' ? 'Skill 编制' : 'Agent / Skill 编制';
}

export function scopedAuthoringTab(view: string | undefined, path: string, search: string) {
  const kind = view === authoringView ? authoringKind(search) : undefined;
  return kind ? { key: path + '?kind=' + kind, title: authoringTitle(kind) } : undefined;
}

export function entityId(value: string | null): string | undefined {
  return value && /^[1-9]\d{0,19}$/.test(value) ? value : undefined;
}

export function workspaceTabPath(path: string, tab: WorkspaceTab) {
  return tab.authoring ? path + '?kind=' + tab.authoring : path;
}
