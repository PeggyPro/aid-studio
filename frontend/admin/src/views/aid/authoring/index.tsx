import { allSkillVersions, suggestVersion } from './suggestVersion';
import { useBlocker } from 'react-router-dom';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Avatar,
  Button,
  Card,
  Col,
  Collapse,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Popconfirm,
  Popover,
  Radio,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  theme,
  Tooltip,
  Tree,
  Typography,
  message
} from 'antd';
import type { DataNode } from 'antd/es/tree';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import {
  AppstoreOutlined,
  BranchesOutlined,
  CodeOutlined,
  DeploymentUnitOutlined,
  RightOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  FileMarkdownOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  SaveOutlined,
  SettingOutlined,
  TeamOutlined,
  ToolOutlined
} from '@ant-design/icons';

import {
  activateSkillVersion,
  createSkill,
  editSkillIdentity,
  getSkillDraft,
  getSkillPublicationSettings,
  getSkillVersion,
  listSkills,
  publishSkillDraft,
  removeSkill,
  restoreSkill,
  saveSkillDraft,
  saveSkillPublicationSettings,
  unpublishUserSkill,
  updateSkillStatus,
  validateSkillDraft,
  type AdminSkillSummary,
  type SkillDraftDetail,
  type SkillId,
  type SkillPackagePayload,
  type SkillTextModelOption,
  type SkillValidationResult,
  type SkillVersionDetail,
  type SkillVersionSummary
} from '@/api/aid/skill';
import {
  RUNTIME_AGENT_PERMISSIONS,
  activateRuntimeAgentVersion,
  createRuntimeAgent,
  editRuntimeAgentIdentity,
  getRuntimeAgentDraft,
  getRuntimeAgentVersion,
  listRuntimeAgentModels,
  listRuntimeAgentSkills,
  listRuntimeAgentTools,
  listRuntimeAgentVersions,
  listRuntimeAgents,
  publishRuntimeAgentDraft,
  removeRuntimeAgent,
  restoreRuntimeAgent,
  saveRuntimeAgentDraft,
  setRuntimeAgentStatus,
  validateRuntimeAgentDraft,
  type RuntimeAgentDraft,
  type RuntimeAgentDraftPayload,
  type RuntimeAgentPackageResource,
  type RuntimeAgentResponsePolicy,
  type RuntimeAgentProtocolVersion,
  type RuntimeAgentSkillOption,
  type RuntimeAgentSummary,
  type RuntimeAgentToolOption,
  type RuntimeAgentValidation,
  type RuntimeAgentVersion,
  type RuntimeId
} from '@/api/aid/assistantRuntimeAgent';
import ImageUpload from '@/components/ImageUpload';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import { authoringTitle } from '@/utils/adminWorkspaces';
import { showReferenceConflict } from '@/utils/apiErrorDetails';
import { wasRequestErrorReported } from '@/utils/request';
import { resolveRuntimeAgentBindings } from '@/utils/runtimeAgentBindingResolver';
import SchemaFieldEditor from '@/views/aid/skill/SchemaFieldEditor';
import SkillPackageManager from '@/views/aid/skill/SkillPackageManager';
import { isSafeReferencePath, MAX_REFERENCE_PATH_LENGTH } from '@/views/aid/skill/skillPackageUtils';
import RuntimeAgentResponsePolicyFields from '@/views/aid/assistant/RuntimeAgentResponsePolicyFields';
import { normalizeRuntimeAgentResponsePolicy } from '@/views/aid/assistant/runtimeAgentResponsePolicy';
import {
  collectPackageDocumentReferences,
  renamePackageDocument,
  type PackageDocumentReference,
  type PackageMarkdownDocument
} from './packageDocumentReferences';
import './style.less';

const { Text, Paragraph } = Typography;
const SEMVER = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:[-+][A-Za-z0-9.-]+)?$/;
const COVER_UPLOAD = `${import.meta.env.VITE_APP_BASE_API || ''}/aid/skill/cover/upload`;
const VERSION_PAGE_SIZE = 100;

type AssetKind = 'agent' | 'skill';
type DocumentKind =
  | 'agent-system'
  | 'agent-reference'
  | 'skill-main'
  | 'skill-system'
  | 'skill-reference';
interface DocumentTarget {
  key: string;
  assetKind: AssetKind;
  assetId: RuntimeId | SkillId;
  documentKind: DocumentKind;
  path: string;
}

type ImmutableDocument =
  | { assetKind: 'agent'; assetId: RuntimeId; data: RuntimeAgentVersion }
  | { assetKind: 'skill'; assetId: SkillId; data: SkillVersionDetail };

interface ReferenceResource {
  filePath: string;
}

function entryDocumentTarget(assetKind: AssetKind, assetId: RuntimeId | SkillId): DocumentTarget {
  return {
    key: `${assetKind}:${String(assetId)}:${assetKind === 'agent' ? 'system' : 'main'}`,
    assetKind,
    assetId,
    documentKind: assetKind === 'agent' ? 'agent-system' : 'skill-main',
    path: assetKind === 'agent' ? 'SP.md' : 'SKILL.md'
  };
}

function referenceDocumentKey(assetKind: AssetKind, assetId: RuntimeId | SkillId, filePath: string) {
  return `${assetKind}:${String(assetId)}:reference:${encodeURIComponent(filePath)}`;
}

function packageDocuments(assetKind: AssetKind, draft: RuntimeAgentDraft | SkillDraftDetail): PackageMarkdownDocument[] {
  return [
    ...(assetKind === 'agent'
      ? [{ path: 'SP.md', content: draft.systemPrompt }]
      : [{ path: 'SKILL.md', content: (draft as SkillDraftDetail).skillMarkdown },
        { path: 'system_prompt.md', content: draft.systemPrompt }]),
    ...draft.resources.map((resource) => ({
      path: resource.filePath,
      content: resource.content,
      markdown: resource.mimeType === 'text/markdown'
    }))
  ];
}

function rewrittenDraft<T extends RuntimeAgentDraft | SkillDraftDetail>(
  assetKind: AssetKind, draft: T, documents: PackageMarkdownDocument[]
): T {
  const contents = new Map(documents.map((document) => [document.path, document.content]));
  const systemPrompt = contents.get(assetKind === 'agent' ? 'SP.md' : 'system_prompt.md') ?? draft.systemPrompt;
  const shared = { ...draft, systemPrompt,
    resources: draft.resources.map((resource) => ({ ...resource, content: contents.get(resource.filePath) ?? resource.content })) };
  return (assetKind === 'agent'
    ? { ...shared, mainMarkdown: systemPrompt, instruction: systemPrompt }
    : { ...shared, skillMarkdown: contents.get('SKILL.md') ?? (draft as SkillDraftDetail).skillMarkdown }) as T;
}

interface AgentAdvancedValues {
  protocolVersion: RuntimeAgentProtocolVersion;
  defaultModelCode: string;
  selectableModelCodes: string[];
  selectedSkillVersionIds: string[];
  selectedToolCodes: string[];
  temperatureBasisPoints: number;
  maxTurns: number;
  maxToolCalls: number;
  checkpointPolicy: 'TURN_BOUNDARY' | 'TOOL_BOUNDARY';
  inputSchemaJson: string;
  outputSchemaJson: string;
  responsePolicy: RuntimeAgentResponsePolicy;
}

interface AgentAdvancedCatalogSnapshot {
  targetId: RuntimeId;
  sequence: number;
  models: SkillTextModelOption[];
  skills: RuntimeAgentSkillOption[];
  tools: RuntimeAgentToolOption[];
}

function errorText(cause: any, fallback: string) {
  return cause?.message || fallback;
}

function showAuthoringError(cause: unknown, fallback: string) {
  if (!wasRequestErrorReported(cause)) message.error(errorText(cause, fallback));
}

function modelOptionLabel(model: SkillTextModelOption) {
  const billing = model.billing as { isFree?: boolean; billingDesc?: string } | undefined;
  return (
    <Space size={6}>
      <Avatar size="small" src={model.modelLogo || model.providerLogo} />
      <span>{model.modelName || model.modelCode}</span>
      <Text type="secondary">{model.providerName || model.modelCode}</Text>
      <Tag color={billing?.isFree ? 'success' : undefined}>
        {billing?.isFree ? '免费' : billing?.billingDesc || '价格未配置'}
      </Tag>
    </Space>
  );
}

function normalizedSkillDraft(draft: SkillDraftDetail): SkillDraftDetail {
  return {
    ...draft,
    skillMarkdown: draft.skillMarkdown || '',
    inputSchemaJson: draft.inputSchemaJson?.trim() ? draft.inputSchemaJson : '{}',
    outputSchemaJson: draft.outputSchemaJson?.trim() ? draft.outputSchemaJson : '{}',
    definitionJson: draft.definitionJson?.trim() ? draft.definitionJson : '{"outputKind":"TEXT"}',
    selectableModelCodes: draft.selectableModelCodes || [],
    resources: (draft.resources || []).map((resource) => ({
      ...resource,
      filePath: resource.filePath || `references/${resource.resourceKey}.md`
    })),
    relations: draft.relations || []
  };
}

function skillPayload(draft: SkillDraftDetail): SkillPackagePayload {
  return {
    skillId: draft.skillId,
    baseVersionId: draft.baseVersionId,
    skillMarkdown: draft.skillMarkdown,
    modelCode: draft.modelCode,
    defaultModelCode: draft.defaultModelCode,
    modelConfigJson: draft.modelConfigJson,
    selectableModelCodes: draft.selectableModelCodes,
    systemPrompt: draft.systemPrompt,
    inputSchemaJson: draft.inputSchemaJson,
    outputSchemaJson: draft.outputSchemaJson,
    definitionJson: draft.definitionJson,
    maxOutputTokens: draft.maxOutputTokens,
    contextWindowTokens: draft.contextWindowTokens,
    safetyMarginTokens: draft.safetyMarginTokens,
    resources: draft.resources,
    relations: draft.relations
  };
}

function normalizedAgentDraft(draft: RuntimeAgentDraft): RuntimeAgentDraft {
  return {
    ...draft,
    resources: draft.resources || [],
    protocolVersion: draft.protocolVersion ?? 'assistant-loop/native-v1',
    responsePolicy: normalizeRuntimeAgentResponsePolicy(draft.responsePolicy, draft.protocolVersion)
  };
}

function agentPayload(draft: RuntimeAgentDraft): RuntimeAgentDraftPayload {
  return {
    agentId: draft.agentId,
    packageVersionCode: draft.packageVersionCode,
    baseVersionId: draft.baseVersionId,
    mainMarkdown: draft.systemPrompt,
    systemPrompt: draft.systemPrompt,
    resources: draft.resources,
    instruction: draft.systemPrompt,
    executorType: 'ASSISTANT_LOOP',
    protocolVersion: draft.protocolVersion ?? 'assistant-loop/native-v1',
    modelCapabilityCode: 'text.chat',
    defaultModelCode: draft.defaultModelCode,
    selectableModelCodes: draft.selectableModelCodes,
    temperatureBasisPoints: draft.temperatureBasisPoints,
    maxTurns: draft.maxTurns,
    maxToolCalls: draft.maxToolCalls,
    checkpointPolicy: draft.checkpointPolicy,
    inputSchemaJson: draft.inputSchemaJson,
    outputSchemaJson: draft.outputSchemaJson,
    responsePolicy: normalizeRuntimeAgentResponsePolicy(draft.responsePolicy, draft.protocolVersion),
    skillBindings: draft.skillBindings || [],
    toolBindings: draft.toolBindings || []
  };
}

async function allRuntimeAgentVersions(agentId: RuntimeId) {
  const rows: RuntimeAgentVersion[] = [];
  for (let pageNum = 1; ; pageNum += 1) {
    const response = await listRuntimeAgentVersions(agentId, pageNum, VERSION_PAGE_SIZE, true);
    const page = response.data || [];
    rows.push(...page);
    const total = Number(response.total);
    if (page.length === 0 || page.length < VERSION_PAGE_SIZE
      || (Number.isFinite(total) && total >= 0 && rows.length >= total)) break;
  }
  return rows;
}

function validationContent(validation?: SkillValidationResult | RuntimeAgentValidation) {
  if (!validation) return null;
  const issues = [...(validation.errors || []), ...(validation.warnings || [])];
  return (
    <Alert
      type={validation.valid ? 'success' : 'error'}
      showIcon
      message={validation.valid ? '草稿校验通过' : '草稿仍有校验错误'}
      description={
        issues.length ? (
          <Space direction="vertical" size={2}>
            {issues.map((item, index) => (
              <Text
                key={`${item.field}:${item.message}:${index}`}
                type={(validation.errors || []).includes(item) ? 'danger' : 'warning'}
              >
                {item.field || 'package'}：{item.message}
              </Text>
            ))}
          </Space>
        ) : (
          '服务端未返回具体问题。'
        )
      }
    />
  );
}

export default function SkillAuthoringPage({ scope, initialSkillId, onRuns, onCreateSkill }: {
  scope?: AssetKind;
  initialSkillId?: string;
  onRuns?: (skillId?: SkillId) => void;
  onCreateSkill?: () => void;
}) {
  const { token } = theme.useToken();
  const { hasPermi } = useAuth();
  const canSkillList = scope !== 'agent' && hasPermi('aid:skill:list');
  const canSkillQuery = scope !== 'agent' && hasPermi('aid:skill:query');
  const canSkillEdit = scope !== 'agent' && hasPermi('aid:skill:edit');
  const canSkillRemove = hasPermi('aid:skill:remove');
  const canSkillRestore = hasPermi('aid:skill:restore');
  const canAgentList = scope !== 'skill' && hasPermi(RUNTIME_AGENT_PERMISSIONS.list);
  const canAgentQuery = scope !== 'skill' && hasPermi(RUNTIME_AGENT_PERMISSIONS.query);
  const canAgentEdit = scope !== 'skill' && hasPermi(RUNTIME_AGENT_PERMISSIONS.edit);
  const canAgentPublish = hasPermi(RUNTIME_AGENT_PERMISSIONS.publish);
  const canAgentActivate = hasPermi(RUNTIME_AGENT_PERMISSIONS.activate);

  const [createForm] = Form.useForm();
  const [identityForm] = Form.useForm();
  const [publishForm] = Form.useForm<{ versionCode: string }>();
  const [referenceForm] = Form.useForm<{ filePath: string; resourceKey: string }>();
  const [moderationForm] = Form.useForm<{ reason: string }>();
  const [agentAdvancedForm] = Form.useForm<AgentAdvancedValues>();
  const advancedSelectedSkillVersionIds = Form.useWatch('selectedSkillVersionIds', agentAdvancedForm) || [];
  const advancedSelectedToolCodes = Form.useWatch('selectedToolCodes', agentAdvancedForm) || [];

  const [keyword, setKeyword] = useState('');
  const [deferredKeyword, setSearchKeyword] = useState('');
  const [skillOwnerType, setSkillOwnerType] = useState<'PLATFORM' | 'USER' | undefined>(scope === 'skill' && !initialSkillId ? 'PLATFORM' : undefined);
  const [skillStatus, setSkillStatus] = useState<'0' | '1'>();
  const [skillOwnerUserId, setSkillOwnerUserId] = useState('');
  const [deferredOwnerUserId, setOwnerFilter] = useState('');
  const [skillPage, setSkillPage] = useState(1);
  const [skillTotal, setSkillTotal] = useState(0);
  const [locatedSkill, setLocatedSkill] = useState(initialSkillId);
  const skillPageSize = 20;
  const [agents, setAgents] = useState<RuntimeAgentSummary[]>([]);
  const [skills, setSkills] = useState<AdminSkillSummary[]>([]);
  const [showDeleted, setShowDeleted] = useState(false);
  const [restoringKey, setRestoringKey] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [catalogReady, setCatalogReady] = useState(false);
  const initialSelection = useRef<string>();
  const [expanded, setExpanded] = useState<React.Key[]>(['agents', 'skills']);
  const [selectedKey, setSelectedKey] = useState<string>();
  const [documentTarget, setDocumentTarget] = useState<DocumentTarget>();
  const [agentDraft, setAgentDraft] = useState<RuntimeAgentDraft>();
  const [skillDraft, setSkillDraft] = useState<SkillDraftDetail>();
  const [editorValue, setEditorValue] = useState('');
  const [dirty, setDirty] = useState(false);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && (
    currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search
  ));
  useEffect(() => {
    if (blocker.state !== 'blocked') return;
    const dialog = Modal.confirm({
      title: '放弃未保存修改？',
      content: '离开编制页会丢失当前工作草稿中的修改。',
      okText: '放弃并离开',
      cancelText: '继续编辑',
      onOk: blocker.proceed,
      onCancel: blocker.reset
    });
    return () => dialog.destroy();
  }, [blocker]);
  const [documentLoading, setDocumentLoading] = useState(false);
  const [documentError, setDocumentError] = useState('');
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionsError, setVersionsError] = useState('');
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<SkillValidationResult | RuntimeAgentValidation>();
  const [versions, setVersions] = useState<Array<SkillVersionSummary | RuntimeAgentVersion>>([]);
  const [viewVersionId, setViewVersionId] = useState<string>('draft');
  const [readonlyVersion, setReadonlyVersion] = useState(false);
  const [immutableDocument, setImmutableDocument] = useState<ImmutableDocument>();
  const [createKind, setCreateKind] = useState<AssetKind>();
  const [creating, setCreating] = useState(false);
  const [identityOpen, setIdentityOpen] = useState(false);
  const [identitySaving, setIdentitySaving] = useState(false);
  const [identityCoverUploading, setIdentityCoverUploading] = useState(false);
  const [identitySyncing, setIdentitySyncing] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [referenceOpen, setReferenceOpen] = useState(false);
  const [referenceEditingPath, setReferenceEditingPath] = useState<string>();
  const [referenceBlockers, setReferenceBlockers] = useState<PackageDocumentReference[]>([]);
  const [referenceJump, setReferenceJump] = useState<{
    assetKind: AssetKind; assetId: RuntimeId | SkillId; versionId: string; path: string; line: number;
  }>();
  const editorRef = useRef<TextAreaRef>(null);
  const [moderationOpen, setModerationOpen] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [packageSkill, setPackageSkill] = useState<AdminSkillSummary>();
  const [allowUserPublicSkills, setAllowUserPublicSkills] = useState<boolean>();
  const [settingsSaving, setSettingsSaving] = useState(false);
  const [advancedCatalog, setAdvancedCatalog] = useState<AgentAdvancedCatalogSnapshot>();
  const [advancedError, setAdvancedError] = useState('');
  const [advancedLoading, setAdvancedLoading] = useState(false);
  const listSequence = useRef(0);
  const documentSequence = useRef(0);
  const referenceDialogSequence = useRef(0);
  const versionsSequence = useRef(0);
  const advancedSequence = useRef(0);
  const advancedTargetId = useRef<RuntimeId>();
  const savingRef = useRef(false);
  const publishingRef = useRef(false);
  const identitySavingRef = useRef(false);
  const identityCoverUploadingRef = useRef(false);
  const identitySyncingRef = useRef(false);

  const selectedAgent = useMemo(
    () =>
      documentTarget?.assetKind === 'agent'
        ? agents.find((item) => String(item.id) === String(documentTarget.assetId))
        : undefined,
    [agents, documentTarget]
  );
  const selectedSkill = useMemo(
    () =>
      documentTarget?.assetKind === 'skill'
        ? skills.find((item) => String(item.id) === String(documentTarget.assetId))
        : undefined,
    [skills, documentTarget]
  );
  const currentAsset = selectedAgent || selectedSkill;
  const activeAgents = useMemo(() => agents.filter((item) => item.delFlag !== '1'), [agents]);
  const deletedAgents = useMemo(() => agents.filter((item) => item.delFlag === '1'), [agents]);
  const activeSkills = useMemo(() => skills.filter((item) => item.delFlag !== '1'), [skills]);
  const deletedSkills = useMemo(() => skills.filter((item) => item.delFlag === '1'), [skills]);
  const catalogTargetIssue = (target: DocumentTarget) => {
    if (loading) return '编制目录正在刷新，当前内容暂时只读。';
    if (loadError) return '编制目录读取失败，当前身份状态无法确认；请刷新目录后再操作。';
    if (target.assetKind === 'agent') {
      if (!canAgentList || !canAgentQuery) return '当前账号已无 Agent 目录或详情权限，旧内容仅保留用于核对。';
      const catalogAgent = agents.find((item) => String(item.id) === String(target.assetId));
      if (!catalogAgent) {
        return '当前 Agent 不在最新目录结果中；可能被搜索条件隐藏、已删除或权限已变化。调整筛选或刷新目录后可继续操作。';
      }
      if (catalogAgent.delFlag === '1') return '当前 Agent 已软删除，只能从“已删除身份”区域恢复。';
      return '';
    }
    if (!canSkillList || !canSkillQuery) return '当前账号已无 Skill 目录或详情权限，旧内容仅保留用于核对。';
    const catalogSkill = skills.find((item) => String(item.id) === String(target.assetId));
    if (!catalogSkill) {
      return '当前 Skill 不在最新目录结果中；可能被搜索条件隐藏、已删除或权限已变化。调整筛选或刷新目录后可继续操作。';
    }
    if (catalogSkill.delFlag === '1') return '当前 Skill 已软删除，只能从“已删除身份”区域恢复。';
    return '';
  };
  const assetContextIssue = documentTarget ? catalogTargetIssue(documentTarget) : '';
  const assetContextReady = Boolean(documentTarget && currentAsset && !assetContextIssue);
  const referenceContextRef = useRef({ documentTarget, agentDraft, skillDraft, assetContextReady, readonlyVersion, canAgentEdit, canSkillEdit });
  referenceContextRef.current = { documentTarget, agentDraft, skillDraft, assetContextReady, readonlyVersion, canAgentEdit, canSkillEdit };
  const requireAssetContext = (operation: string) => {
    if (assetContextReady) return true;
    message.warning(assetContextIssue || `当前身份未就绪，无法${operation}`);
    return false;
  };
  useEffect(() => { setReferenceBlockers([]); }, [agentDraft, skillDraft, viewVersionId,
    documentTarget?.assetKind, documentTarget?.assetId]);
  useEffect(() => {
    if (referenceJump && (documentTarget?.assetKind !== referenceJump.assetKind
      || String(documentTarget?.assetId) !== String(referenceJump.assetId)
      || viewVersionId !== referenceJump.versionId)) { setReferenceJump(undefined); return; }
    if (!referenceJump || documentTarget?.path !== referenceJump.path || documentLoading || documentError) return;
    const textarea = editorRef.current?.resizableTextArea?.textArea;
    if (!textarea) return;
    const lines = editorValue.split('\n');
    const offset = lines.slice(0, referenceJump.line - 1).reduce((sum, line) => sum + line.length + 1, 0);
    textarea.focus();
    textarea.setSelectionRange(offset, offset + (lines[referenceJump.line - 1]?.length || 0));
    const lineHeight = Number.parseFloat(getComputedStyle(textarea).lineHeight) || 23;
    textarea.scrollTop = Math.max(0, (referenceJump.line - 1) * lineHeight - textarea.clientHeight / 2);
    setReferenceJump(undefined);
  }, [referenceJump, documentTarget?.path, documentTarget?.assetKind, documentTarget?.assetId,
    viewVersionId, documentLoading, documentError, editorValue]);
  const advancedCatalogReady = Boolean(
    advancedOpen
    && assetContextReady
    && !advancedLoading
    && !advancedError
    && advancedCatalog
    && advancedCatalog.sequence === advancedSequence.current
    && String(advancedCatalog.targetId) === String(advancedTargetId.current)
    && String(advancedCatalog.targetId) === String(selectedAgent?.id)
    && documentTarget?.assetKind === 'agent'
    && String(advancedCatalog.targetId) === String(documentTarget.assetId)
    && String(advancedCatalog.targetId) === String(agentDraft?.agentId)
  );
  const agentModels = advancedCatalogReady ? advancedCatalog!.models : [];
  const agentSkills = advancedCatalogReady ? advancedCatalog!.skills : [];
  const agentTools = advancedCatalogReady ? advancedCatalog!.tools : [];
  const advancedBindingResolution = advancedCatalogReady
    ? resolveRuntimeAgentBindings(
      advancedSelectedSkillVersionIds,
      advancedSelectedToolCodes,
      agentSkills,
      agentTools
    )
    : undefined;
  const missingAdvancedSkillIds = advancedBindingResolution?.unresolvedSkillVersionIds || [];
  const missingAdvancedToolCodes = advancedBindingResolution?.unresolvedToolCodes || [];
  const missingAdvancedSkillLabels = missingAdvancedSkillIds.map((id) => {
    const binding = agentDraft?.skillBindings?.find((item) => String(item.skillVersionId) === id);
    return `${binding?.skillCode || '未知 Skill'} · 版本 ID ${id}`;
  });
  const missingAdvancedToolLabels = missingAdvancedToolCodes.map((code) => {
    const binding = agentDraft?.toolBindings?.find((item) => item.toolCode === code);
    return `${code}${binding?.contractVersion ? `@${binding.contractVersion}` : ''}`;
  });
  const advancedHasMissingBindings = Boolean(advancedBindingResolution && !advancedBindingResolution.complete);
  const agentSkillSelectOptions = [
    ...agentSkills.map((skill) => ({
      value: String(skill.skillVersionId),
      label: `${skill.name} · ${skill.skillCode}@${skill.versionCode}`
    })),
    ...missingAdvancedSkillIds.map((id, index) => ({
      value: id,
      label: `不可用历史绑定 · ${missingAdvancedSkillLabels[index]}`
    }))
  ];
  const agentToolSelectOptions = [
    ...agentTools.map((tool) => ({
      value: tool.code,
      label: `${tool.name} · ${tool.code}@${tool.contractVersion}`
    })),
    ...missingAdvancedToolCodes.map((code, index) => ({
      value: code,
      label: `不可用历史绑定 · ${missingAdvancedToolLabels[index]}`
    }))
  ];

  const changeDirectory = (update: () => void, page = 1) => {
    if (savingRef.current || publishingRef.current) return;
    const apply = () => {
      listSequence.current++;
      documentSequence.current++;
      versionsSequence.current++;
      setDocumentTarget(undefined);
      setSelectedKey(undefined);
      setAgentDraft(undefined);
      setSkillDraft(undefined);
      setEditorValue('');
      setDirty(false);
      setSkills([]);
      setCatalogReady(false);
      setLocatedSkill(undefined);
      setSkillPage(page);
      update();
    };
    if (dirty) Modal.confirm({ title: '放弃未保存修改并切换目录？', onOk: apply, okText: '放弃并切换', cancelText: '继续编辑' });
    else apply();
  };

  const loadCatalog = useCallback(
    async (force = false) => {
      const current = ++listSequence.current;
      let loadedAgents: RuntimeAgentSummary[] | undefined;
      let loadedSkills: AdminSkillSummary[] | undefined;
      setLoading(true);
      setLoadError('');
      const requests: Promise<unknown>[] = [];
      if (!canAgentList) {
        loadedAgents = [];
        if (current === listSequence.current) setAgents([]);
      }
      if (canAgentList)
        requests.push(
          (async () => {
            const rows: RuntimeAgentSummary[] = [];
            for (let pageNum = 1; ; pageNum += 1) {
              const response = await listRuntimeAgents({
                pageNum, pageSize: 100, keyword: deferredKeyword || undefined, includeDeleted: showDeleted
              }, force);
              const page = response.data || [];
              rows.push(...page);
              const total = Number(response.total);
              if (page.length === 0 || page.length < 100
                || (Number.isFinite(total) && total >= 0 && rows.length >= total)) break;
            }
            loadedAgents = rows;
            if (current === listSequence.current) setAgents(rows);
          })()
        );
      if (!canSkillList) {
        loadedSkills = [];
        if (current === listSequence.current) setSkills([]);
      }
      if (canSkillList)
        requests.push(
          (async () => {
            const response = await listSkills({
              pageNum: skillPage, pageSize: skillPageSize, skillId: locatedSkill,
              keyword: deferredKeyword || undefined, includeDeleted: showDeleted,
              status: skillStatus, ownerType: skillOwnerType,
              ownerUserId: skillOwnerType === 'USER' && deferredOwnerUserId ? deferredOwnerUserId : undefined
            }, { force });
            loadedSkills = response.data || [];
            if (current === listSequence.current) {
              const total = Number(response.total);
              setSkillTotal(total);
              setSkills(loadedSkills);
              const lastPage = Math.max(1, Math.ceil(total / skillPageSize));
              if (skillPage > lastPage) setSkillPage(lastPage);
            }
          })()
        );
      try {
        await Promise.all(requests);
        if (current === listSequence.current) {
          setCatalogReady(true);
          return { agents: loadedAgents, skills: loadedSkills };
        }
      } catch (cause: any) {
        if (current === listSequence.current) setLoadError(errorText(cause, '编制目录读取失败'));
      } finally {
        if (current === listSequence.current) setLoading(false);
      }
    },
    [canAgentList, canSkillList, deferredKeyword, deferredOwnerUserId, showDeleted, skillOwnerType, skillStatus, skillPage, locatedSkill]
  );

  useEffect(() => {
    void loadCatalog();
  }, [loadCatalog]);
  useEffect(() => {
    if (!canSkillQuery) return;
    getSkillPublicationSettings()
      .then((response) => setAllowUserPublicSkills(response.data.allowUserPublicSkills))
      .catch(() => setAllowUserPublicSkills(undefined));
  }, [canSkillQuery]);

  const visibleAgentResources = immutableDocument?.assetKind === 'agent'
    && readonlyVersion
    && String(immutableDocument.assetId) === String(documentTarget?.assetId)
    ? immutableDocument.data.resources
    : agentDraft?.resources;
  const visibleSkillResources = immutableDocument?.assetKind === 'skill'
    && readonlyVersion
    && String(immutableDocument.assetId) === String(documentTarget?.assetId)
    ? immutableDocument.data.resources
    : skillDraft?.resources;

  const targetMap = useMemo(() => {
    const map = new Map<string, DocumentTarget>();
    activeAgents.forEach((agent) => {
      const system = `agent:${String(agent.id)}:system`;
      map.set(`agent:${String(agent.id)}`, entryDocumentTarget('agent', agent.id));
      map.set(system, {
        key: system,
        assetKind: 'agent',
        assetId: agent.id,
        documentKind: 'agent-system',
        path: 'SP.md'
      });
      if (visibleAgentResources && String(documentTarget?.assetId) === String(agent.id)) {
        visibleAgentResources.forEach((resource) => {
          const key = referenceDocumentKey('agent', agent.id, resource.filePath);
          map.set(key, {
            key,
            assetKind: 'agent',
            assetId: agent.id,
            documentKind: 'agent-reference',
            path: resource.filePath
          });
        });
      }
    });
    activeSkills.forEach((skill) => {
      const main = `skill:${String(skill.id)}:main`;
      const system = `skill:${String(skill.id)}:system`;
      map.set('skill:' + skill.id, { ...entryDocumentTarget('skill', skill.id), key: 'skill:' + skill.id });
      map.set('skill:' + skill.id + ':references', { ...entryDocumentTarget('skill', skill.id), key: 'skill:' + skill.id + ':references' });
      map.set(main, { key: main, assetKind: 'skill', assetId: skill.id, documentKind: 'skill-main', path: 'SKILL.md' });
      map.set(system, {
        key: system,
        assetKind: 'skill',
        assetId: skill.id,
        documentKind: 'skill-system',
        path: 'system_prompt.md'
      });
      if (visibleSkillResources && String(documentTarget?.assetId) === String(skill.id)) {
        visibleSkillResources.forEach((resource) => {
          const key = referenceDocumentKey('skill', skill.id, resource.filePath);
          map.set(key, {
            key,
            assetKind: 'skill',
            assetId: skill.id,
            documentKind: 'skill-reference',
            path: resource.filePath
          });
        });
      }
    });
    return map;
  }, [activeAgents, activeSkills, documentTarget?.assetId, visibleAgentResources, visibleSkillResources]);

  const treeData = useMemo<DataNode[]>(() => {
    const label = (name: string, code: string, status: '0' | '1') => (
      <Tooltip title={`${name} · ${code}`} placement="right">
        <span className="authoring-tree__asset-label">
          <span>{name}</span>
          <Text type="secondary">{code}</Text>
          {status === '1' && <Tag>已停用</Tag>}
        </span>
      </Tooltip>
    );
    const groups: DataNode[] = [];
    if (canAgentList) {
      groups.push({
        key: 'agents',
        title: `Agents（${activeAgents.length}）`,
        icon: <TeamOutlined />,
        selectable: false,
        children: activeAgents.map((agent) => ({
          key: `agent:${String(agent.id)}`,
          title: label(agent.name, agent.agentCode, agent.status),
          icon: <ToolOutlined />,
          selectable: true,
          children: [
            {
              key: `agent:${String(agent.id)}:system`,
              title: 'SP.md',
              icon: <FileMarkdownOutlined />,
              isLeaf: true
            },
            {
              key: `agent:${String(agent.id)}:references`,
              title: `references${visibleAgentResources && String(documentTarget?.assetId) === String(agent.id)
                ? `（${visibleAgentResources.length}）` : ''}`,
              icon: <FolderOpenOutlined />,
              selectable: false,
              children:
                visibleAgentResources && String(documentTarget?.assetId) === String(agent.id)
                  ? visibleAgentResources.map((resource) => ({
                      key: referenceDocumentKey('agent', agent.id, resource.filePath),
                      title: resource.filePath.replace(/^references\//, ''),
                      icon: <FileMarkdownOutlined />,
                      isLeaf: true
                    }))
                  : []
            }
          ]
        }))
      });
    }
    if (canSkillList) {
      groups.push({
        key: 'skills',
        title: `Skills（${activeSkills.length}）`,
        icon: <FolderOpenOutlined />,
        selectable: false,
        children: activeSkills.map((skill) => ({
          key: `skill:${String(skill.id)}`,
          title: label(skill.name, skill.skillCode, skill.status),
          icon: <ToolOutlined />,
          selectable: true,
          children: [
            { key: `skill:${String(skill.id)}:main`, title: '使用说明 · SKILL.md', icon: <FileMarkdownOutlined />, isLeaf: true },
            {
              key: `skill:${String(skill.id)}:system`,
              title: '系统提示词 · system_prompt.md',
              icon: <FileMarkdownOutlined />,
              isLeaf: true
            },
            {
              key: `skill:${String(skill.id)}:references`,
              title: `参考文件${visibleSkillResources && String(documentTarget?.assetId) === String(skill.id)
                ? `（${visibleSkillResources.length}）` : ''}`,
              icon: <FolderOpenOutlined />,
              selectable: true,
              children:
                visibleSkillResources && String(documentTarget?.assetId) === String(skill.id)
                  ? visibleSkillResources.map((resource) => ({
                      key: referenceDocumentKey('skill', skill.id, resource.filePath),
                      title: resource.filePath.replace(/^references\//, ''),
                      icon: <FileMarkdownOutlined />,
                      isLeaf: true
                    }))
                  : []
            }
          ]
        }))
      });
    }
    return groups.length === 1 ? groups[0].children || [] : groups;
  }, [activeAgents, activeSkills, canAgentList, canSkillList, documentTarget?.assetId, visibleAgentResources, visibleSkillResources]);

  const readTargetContent = (
    target: DocumentTarget,
    document: SkillDraftDetail | SkillVersionDetail | RuntimeAgentDraft | RuntimeAgentVersion
  ) => {
    if (target.assetKind === 'agent') {
      const agent = document as RuntimeAgentDraft | RuntimeAgentVersion;
      if (target.documentKind === 'agent-system') return agent.systemPrompt || '';
      return agent.resources.find((resource) => resource.filePath === target.path)?.content ?? '';
    }
    const skill = document as SkillDraftDetail | SkillVersionDetail;
    if (target.documentKind === 'skill-main') return skill.skillMarkdown || '';
    if (target.documentKind === 'skill-system') return skill.systemPrompt || '';
    return skill.resources.find((resource) => resource.filePath === target.path)?.content ?? '';
  };

  const findUniqueReferenceIndex = (
    resources: ReferenceResource[], filePath: string, missingMessage = `当前草稿不包含 ${filePath}`
  ) => {
    const matches: number[] = [];
    resources.forEach((resource, index) => {
      if (resource.filePath === filePath) matches.push(index);
    });
    if (matches.length === 0) throw new Error(missingMessage);
    if (matches.length > 1) throw new Error(`逻辑文件路径重复，无法安全定位：${filePath}`);
    return matches[0];
  };

  const resolveDocumentTarget = (
    target: DocumentTarget,
    resources: ReferenceResource[],
    fallbackMissingReference = false
  ): { target: DocumentTarget; missingReferencePath?: string } => {
    const isReference = target.documentKind === 'agent-reference' || target.documentKind === 'skill-reference';
    if (!isReference) return { target };
    const matches = resources.filter((resource) => resource.filePath === target.path);
    if (matches.length > 1) throw new Error(`逻辑文件路径重复，无法安全定位：${target.path}`);
    if (matches.length === 0) {
      if (fallbackMissingReference) {
        return { target: entryDocumentTarget(target.assetKind, target.assetId), missingReferencePath: target.path };
      }
      throw new Error(`当前文件包不包含 ${target.path}`);
    }
    return {
      target: {
        ...target,
        key: referenceDocumentKey(target.assetKind, target.assetId, target.path)
      }
    };
  };

  const validateReferencePaths = (resources: ReferenceResource[], label: string) => {
    const paths = new Set<string>();
    resources.forEach((resource) => {
      if (!resource.filePath || !isSafeReferencePath(resource.filePath)) {
        throw new Error(`${label}包含无效的受控参考文件路径`);
      }
      if (paths.has(resource.filePath)) {
        throw new Error(`${label}包含重复逻辑文件路径：${resource.filePath}`);
      }
      paths.add(resource.filePath);
    });
  };

  const validateSkillFiles = (document: SkillDraftDetail | SkillVersionDetail, label: string) => {
    if (typeof document.skillMarkdown !== 'string'
      || !Array.isArray(document.resources)
      || document.resources.some((resource) => !resource.filePath)) {
      throw new Error(`${label}尚未返回 SKILL.md 或受控参考文件路径`);
    }
    validateReferencePaths(document.resources, label);
  };

  const validateAgentFiles = (document: RuntimeAgentDraft | RuntimeAgentVersion, label: string) => {
    if (
      typeof document.mainMarkdown !== 'string'
      || typeof document.systemPrompt !== 'string'
      || !Array.isArray(document.resources)
      || document.resources.some((resource) => !resource.filePath)
    ) {
      throw new Error(`${label}尚未返回 Agent 主文件、系统提示词或受控参考文件路径`);
    }
    validateReferencePaths(document.resources, label);
  };

  const loadVersionHistory = async (target: DocumentTarget, reset = false) => {
    const current = ++versionsSequence.current;
    if (reset) setVersions([]);
    setVersionsLoading(true);
    setVersionsError('');
    try {
      if (target.assetKind === 'agent') {
        if (!canAgentQuery) throw new Error('没有 Agent 详情权限');
        const rows = await allRuntimeAgentVersions(target.assetId);
        if (current === versionsSequence.current) setVersions(rows);
      } else {
        if (!canSkillQuery) throw new Error('没有 Skill 详情权限');
        const rows = await allSkillVersions(target.assetId);
        if (current === versionsSequence.current) setVersions(rows);
      }
    } catch (cause: any) {
      if (current === versionsSequence.current)
        setVersionsError(errorText(cause, '不可变版本列表读取失败'));
    } finally {
      if (current === versionsSequence.current) setVersionsLoading(false);
    }
  };

  const commitImmutableDocument = (
    target: DocumentTarget,
    version: SkillVersionDetail | RuntimeAgentVersion
  ) => {
    let resolvedTarget: DocumentTarget;
    if (target.assetKind === 'agent') {
      const agentVersion = version as RuntimeAgentVersion;
      if (String(agentVersion.agentId) !== String(target.assetId)) {
        throw new Error('Agent 版本与当前身份不匹配');
      }
      validateAgentFiles(agentVersion, '此 Agent 版本');
      resolvedTarget = resolveDocumentTarget(target, agentVersion.resources).target;
      setImmutableDocument({ assetKind: 'agent', assetId: target.assetId, data: agentVersion });
    } else {
      const skillVersion = version as SkillVersionDetail;
      if (String(skillVersion.skillId) !== String(target.assetId)) {
        throw new Error('Skill 版本与当前身份不匹配');
      }
      validateSkillFiles(skillVersion, '此版本');
      resolvedTarget = resolveDocumentTarget(target, skillVersion.resources).target;
      setImmutableDocument({ assetKind: 'skill', assetId: target.assetId, data: skillVersion });
    }
    setSelectedKey(resolvedTarget.key);
    setDocumentTarget(resolvedTarget);
    setAgentDraft(undefined);
    setSkillDraft(undefined);
    setViewVersionId(String(version.id));
    setReadonlyVersion(true);
    setEditorValue(readTargetContent(resolvedTarget, version));
    setDocumentError('');
    setDirty(false);
    setValidation(undefined);
  };

  const loadImmutableDocument = async (
    target: DocumentTarget,
    versionId: string,
    publishedVersion?: SkillVersionDetail | RuntimeAgentVersion
  ) => {
    const current = ++documentSequence.current;
    const previousError = documentError;
    setDocumentLoading(true);
    setDocumentError('');
    try {
      if (target.assetKind === 'agent' && !canAgentQuery) throw new Error('没有 Agent 详情权限');
      if (target.assetKind === 'skill' && !canSkillQuery) throw new Error('没有 Skill 详情权限');
      const version = publishedVersion || (target.assetKind === 'agent'
        ? (await getRuntimeAgentVersion(versionId, true)).data
        : (await getSkillVersion(versionId, { force: true })).data);
      if (current !== documentSequence.current) return;
      if (String(version.id) !== versionId) throw new Error('服务端返回了不匹配的不可变版本');
      commitImmutableDocument(target, version);
    } catch (cause: any) {
      if (current === documentSequence.current) {
        setDocumentError(previousError);
        showAuthoringError(cause, '版本内容读取失败');
      }
    } finally {
      if (current === documentSequence.current) setDocumentLoading(false);
    }
  };

  const loadDocument = async (target: DocumentTarget) => {
    const targetIssue = catalogTargetIssue(target);
    if (targetIssue) {
      message.warning(targetIssue);
      return;
    }
    const current = ++documentSequence.current;
    versionsSequence.current += 1;
    setSelectedKey(target.key);
    setDocumentTarget(target);
    setDocumentLoading(true);
    setDocumentError('');
    setVersionsError('');
    setValidation(undefined);
    setViewVersionId('draft');
    setReadonlyVersion(false);
    setImmutableDocument(undefined);
    setAgentDraft(undefined);
    setSkillDraft(undefined);
    setEditorValue('');
    setDirty(false);
    void loadVersionHistory(target, true);
    try {
      if (target.assetKind === 'agent') {
        if (!canAgentQuery) throw new Error('没有 Agent 详情权限');
        const draftResponse = await getRuntimeAgentDraft(target.assetId, undefined, true);
        if (current !== documentSequence.current) return;
        validateAgentFiles(draftResponse.data, '服务端草稿');
        const normalized = normalizedAgentDraft(draftResponse.data);
        const resolved = resolveDocumentTarget(target, normalized.resources, true);
        setAgentDraft(normalized);
        setDocumentTarget(resolved.target);
        setSelectedKey(resolved.target.key);
        setEditorValue(readTargetContent(resolved.target, normalized));
        if (resolved.missingReferencePath) {
          message.warning(`当前 Agent 草稿不包含 ${resolved.missingReferencePath}，已打开 SP.md`);
        }
      } else {
        if (!canSkillQuery) throw new Error('没有 Skill 详情权限');
        const draftResponse = await getSkillDraft(target.assetId, undefined, { force: true });
        if (current !== documentSequence.current) return;
        validateSkillFiles(draftResponse.data, '服务端草稿');
        const normalized = normalizedSkillDraft(draftResponse.data);
        const resolved = resolveDocumentTarget(target, normalized.resources, true);
        setSkillDraft(normalized);
        setDocumentTarget(resolved.target);
        setSelectedKey(resolved.target.key);
        setEditorValue(readTargetContent(resolved.target, normalized));
        if (resolved.missingReferencePath) {
          message.warning(`当前 Skill 草稿不包含 ${resolved.missingReferencePath}，已打开 SKILL.md`);
        }
      }
    } catch (cause: any) {
      if (current === documentSequence.current)
        setDocumentError(`${errorText(cause, '草稿内容未就绪')}。仍可从版本下拉框查看并切换有效的不可变版本。`);
    } finally {
      if (current === documentSequence.current) setDocumentLoading(false);
    }
  };

  const requestSelect = (target: DocumentTarget) => {
    if (savingRef.current) {
      message.warning('草稿正在保存，请等待保存完成');
      return;
    }
    if (publishingRef.current) {
      message.warning('草稿正在发布，请等待发布完成');
      return;
    }
    const targetIssue = catalogTargetIssue(target);
    if (targetIssue) {
      message.warning(targetIssue);
      return;
    }
    if (target.key === documentTarget?.key) return;
    const sameAsset = documentTarget?.assetKind === target.assetKind
      && String(documentTarget.assetId) === String(target.assetId);
    const workingDocument = target.assetKind === 'agent' ? agentDraft : skillDraft;
    const workingDocumentId = target.assetKind === 'agent'
      ? (workingDocument as RuntimeAgentDraft | undefined)?.agentId
      : (workingDocument as SkillDraftDetail | undefined)?.skillId;
    if (sameAsset && viewVersionId === 'draft' && !readonlyVersion
      && workingDocument && String(workingDocumentId) === String(target.assetId)) {
      documentSequence.current += 1;
      setDocumentLoading(false);
      setDocumentError('');
      setSelectedKey(target.key);
      setDocumentTarget(target);
      setEditorValue(readTargetContent(target, workingDocument));
      return;
    }
    if (sameAsset && readonlyVersion && immutableDocument
      && immutableDocument.assetKind === target.assetKind
      && String(immutableDocument.assetId) === String(target.assetId)
      && String(immutableDocument.data.id) === viewVersionId) {
      documentSequence.current += 1;
      setDocumentLoading(false);
      setDocumentError('');
      setSelectedKey(target.key);
      setDocumentTarget(target);
      setEditorValue(readTargetContent(target, immutableDocument.data));
      return;
    }
    if (!dirty) {
      void loadDocument(target);
      return;
    }
    Modal.confirm({
      title: '放弃未保存修改？',
      content: '切换到另一个 Agent 或 Skill 会丢失当前工作草稿中的修改。',
      okText: '放弃并切换',
      cancelText: '继续编辑',
      onOk: () => void loadDocument(target)
    });
  };

  const requestSelectRef = useRef(requestSelect);
  requestSelectRef.current = requestSelect;
  useEffect(() => {
    if (!locatedSkill || !initialSkillId || initialSelection.current === initialSkillId || !catalogReady || loading || loadError) return;
    const target = targetMap.get('skill:' + initialSkillId);
    if (!target || !canSkillList || !canSkillQuery) return;
    setExpanded(keys => [...new Set([...keys, 'skill:' + initialSkillId])]);
    initialSelection.current = initialSkillId;
    requestSelectRef.current(target);
  }, [locatedSkill, initialSkillId, catalogReady, loading, loadError, targetMap, canSkillList, canSkillQuery]);

  const openSkillPackage = () => {
    if (!requireAssetContext('打开完整 Skill 配置') || !selectedSkill) return;
    if (!dirty) {
      setPackageSkill(selectedSkill);
      return;
    }
    Modal.confirm({
      title: '放弃未保存修改？',
      content: '完整 Skill 配置保存后会重新读取草稿，当前工作文档中的修改将丢失。',
      okText: '放弃并继续',
      cancelText: '继续编辑',
      onOk: () => setPackageSkill(selectedSkill)
    });
  };

  const applyEditorValue = (value: string) => {
    if (!documentTarget) return;
    const canEditTarget = documentTarget.assetKind === 'agent' ? canAgentEdit : canSkillEdit;
    if (savingRef.current || publishingRef.current || readonlyVersion || !assetContextReady
      || documentLoading || !!documentError || !canEditTarget) return;
    const acceptValue = () => {
      setEditorValue(value);
      setDirty(true);
      setValidation(undefined);
    };
    if (documentTarget.assetKind === 'agent') {
      if (!agentDraft || String(agentDraft.agentId) !== String(documentTarget.assetId)) return;
      if (documentTarget.documentKind === 'agent-reference') {
        try {
          const index = findUniqueReferenceIndex(agentDraft.resources, documentTarget.path);
          const resources = [...agentDraft.resources];
          resources[index] = { ...resources[index], content: value };
          setAgentDraft({ ...agentDraft, resources });
          acceptValue();
        } catch (cause: any) {
          setDocumentError(errorText(cause, '当前参考文件无法安全定位'));
        }
        return;
      }
      setAgentDraft((current) => {
        if (!current || String(current.agentId) !== String(documentTarget.assetId)) return current;
        if (documentTarget.documentKind === 'agent-system') {
          return { ...current, mainMarkdown: value, systemPrompt: value, instruction: value };
        }
        return current;
      });
      acceptValue();
      return;
    }
    if (!skillDraft || String(skillDraft.skillId) !== String(documentTarget.assetId)) return;
    if (documentTarget.documentKind === 'skill-reference') {
      try {
        const index = findUniqueReferenceIndex(skillDraft.resources, documentTarget.path);
        const resources = [...skillDraft.resources];
        resources[index] = { ...resources[index], content: value };
        setSkillDraft({ ...skillDraft, resources });
        acceptValue();
      } catch (cause: any) {
        setDocumentError(errorText(cause, '当前参考文件无法安全定位'));
      }
      return;
    }
    setSkillDraft((current) => {
      if (!current || String(current.skillId) !== String(documentTarget.assetId)) return current;
      if (documentTarget.documentKind === 'skill-main') return { ...current, skillMarkdown: value };
      if (documentTarget.documentKind === 'skill-system') return { ...current, systemPrompt: value };
      return current;
    });
    acceptValue();
  };

  const saveDraft = async () => {
    if (savingRef.current) return;
    if (!requireAssetContext('保存草稿')) return;
    savingRef.current = true;
    setSaving(true);
    try {
      if (documentTarget?.assetKind === 'agent' && agentDraft) {
        const response = await saveRuntimeAgentDraft({
          ...agentPayload(agentDraft),
          draftId: agentDraft.draftId,
          draftDigest: agentDraft.draftDigest
        });
        validateAgentFiles(response.data, '服务端保存结果');
        const normalized = normalizedAgentDraft(response.data);
        const resolved = resolveDocumentTarget(documentTarget, normalized.resources, true);
        setAgentDraft(normalized);
        setDocumentTarget(resolved.target);
        setSelectedKey(resolved.target.key);
        setEditorValue(readTargetContent(resolved.target, normalized));
        if (resolved.missingReferencePath) {
          message.warning(`保存后的 Agent 草稿不包含 ${resolved.missingReferencePath}，已打开 SP.md`);
        }
      } else if (documentTarget?.assetKind === 'skill' && skillDraft) {
        const response = await saveSkillDraft({
          ...skillPayload(skillDraft),
          draftId: skillDraft.draftId,
          draftDigest: skillDraft.draftDigest
        });
        validateSkillFiles(response.data, '服务端保存结果');
        const normalized = normalizedSkillDraft(response.data);
        const resolved = resolveDocumentTarget(documentTarget, normalized.resources, true);
        setSkillDraft(normalized);
        setDocumentTarget(resolved.target);
        setSelectedKey(resolved.target.key);
        setEditorValue(readTargetContent(resolved.target, normalized));
        if (resolved.missingReferencePath) {
          message.warning(`保存后的 Skill 草稿不包含 ${resolved.missingReferencePath}，已打开 SKILL.md`);
        }
      } else throw new Error('没有可保存的草稿');
      setDirty(false);
      setValidation(undefined);
      message.success('草稿已保存');
    } catch (cause: any) {
      showAuthoringError(cause, '草稿保存失败');
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  const convertAgentToFollowupVersion = async () => {
    if (savingRef.current || publishingRef.current) return;
    if (!requireAssetContext('转换后续版本草稿')) return;
    const target = documentTarget;
    const currentDraft = agentDraft;
    if (target?.assetKind !== 'agent' || readonlyVersion || !canAgentEdit
      || !currentDraft?.packageVersionCode) return;
    savingRef.current = true;
    setSaving(true);
    try {
      const response = await saveRuntimeAgentDraft({
        ...agentPayload(currentDraft),
        packageVersionCode: null,
        draftId: currentDraft.draftId,
        draftDigest: currentDraft.draftDigest
      });
      validateAgentFiles(response.data, '服务端转换结果');
      if (response.data.packageVersionCode || !response.data.draftDigest
        || response.data.draftDigest === currentDraft.draftDigest) {
        throw new Error('服务端未返回已解除版本锁的新草稿摘要，请刷新后重试');
      }
      const latest = referenceContextRef.current;
      if (!latest.assetContextReady || latest.readonlyVersion || !latest.canAgentEdit
        || latest.agentDraft !== currentDraft || latest.documentTarget?.assetKind !== 'agent'
        || String(latest.documentTarget.assetId) !== String(target.assetId)) {
        message.warning('后续版本草稿已保存；当前页面上下文已变化，请重新打开该 Agent');
        return;
      }
      const normalized = normalizedAgentDraft(response.data);
      const resolved = resolveDocumentTarget(target, normalized.resources, true);
      setAgentDraft(normalized);
      setDocumentTarget(resolved.target);
      setSelectedKey(resolved.target.key);
      setEditorValue(readTargetContent(resolved.target, normalized));
      setDirty(false);
      setValidation(undefined);
      setPublishOpen(false);
      publishForm.resetFields();
      if (resolved.missingReferencePath) {
        message.warning(`转换后的 Agent 草稿不包含 ${resolved.missingReferencePath}，已打开 SP.md`);
      }
      message.success('已转为后续版本草稿；请填写下一版本号并重新校验');
    } catch (cause: any) {
      showAuthoringError(cause, '转换后续版本草稿失败');
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  const requestConvertAgentToFollowupVersion = () => {
    if (!agentDraft?.packageVersionCode) return;
    Modal.confirm({
      title: '转为后续版本草稿？',
      content: `当前草稿固定为内置包版本 ${agentDraft.packageVersionCode}。转换会通过摘要并发校验保存当前全部配置和参考文件，并解除固定版本；历史正式版本不会改变。转换后请填写下一版本号并重新校验。`,
      okText: '保存并转换',
      cancelText: '取消',
      onOk: () => convertAgentToFollowupVersion()
    });
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's'
        && dirty && !readonlyVersion && !editorUnavailable) {
        event.preventDefault();
        void saveDraft();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  const validateDraft = async () => {
    if (!requireAssetContext('校验草稿')) return;
    const target = documentTarget;
    const draft = target?.assetKind === 'agent' ? agentDraft : skillDraft;
    const isCurrent = () => {
      const latest = referenceContextRef.current;
      return target && draft && latest.assetContextReady && !latest.readonlyVersion
        && latest.documentTarget?.assetKind === target.assetKind
        && String(latest.documentTarget.assetId) === String(target.assetId)
        && (target.assetKind === 'agent' ? latest.agentDraft === draft && latest.canAgentEdit
          : latest.skillDraft === draft && latest.canSkillEdit);
    };
    setValidating(true);
    try {
      const result = target?.assetKind === 'agent' && draft
        ? (await validateRuntimeAgentDraft(agentPayload(draft as RuntimeAgentDraft))).data
        : target?.assetKind === 'skill' && draft
          ? (await validateSkillDraft(skillPayload(draft as SkillDraftDetail), { force: true })).data
          : undefined;
      if (!result) throw new Error('没有可校验的草稿');
      if (isCurrent()) setValidation(result);
    } catch (cause: any) {
      if (isCurrent()) showAuthoringError(cause, '草稿校验失败');
    } finally {
      setValidating(false);
    }
  };

  const changeVersion = async (value: string) => {
    if (!documentTarget) return;
    if (!requireAssetContext('切换版本')) return;
    if (value === 'draft') {
      await loadDocument(documentTarget);
      return;
    }
    await loadImmutableDocument(documentTarget, value);
  };

  const requestVersionChange = (value: string) => {
    if (savingRef.current) {
      message.warning('草稿正在保存，请等待保存完成');
      return;
    }
    if (publishingRef.current) {
      message.warning('草稿正在发布，请等待发布完成');
      return;
    }
    if (value === viewVersionId) return;
    if (!dirty) {
      void changeVersion(value);
      return;
    }
    Modal.confirm({
      title: '放弃未保存修改？',
      content: '切换草稿或历史版本会丢失当前工作文档中的修改。',
      okText: '放弃并切换',
      cancelText: '继续编辑',
      onOk: () => void changeVersion(value)
    });
  };

  const publish = async () => {
    if (publishingRef.current) return;
    if (!requireAssetContext('发布草稿')) return;
    publishingRef.current = true;
    setPublishing(true);
    const target = documentTarget;
    const targetSequence = documentSequence.current;
    try {
      const { versionCode } = await publishForm.validateFields();
      let publishedVersion: SkillVersionDetail | RuntimeAgentVersion;
      if (documentTarget?.assetKind === 'agent' && agentDraft?.draftId && agentDraft.draftDigest) {
        publishedVersion = (await publishRuntimeAgentDraft(
          agentDraft.draftId, agentDraft.draftDigest, versionCode
        )).data;
      } else if (documentTarget?.assetKind === 'skill' && skillDraft?.draftId && skillDraft.draftDigest) {
        publishedVersion = (await publishSkillDraft(
          skillDraft.draftId, skillDraft.draftDigest, versionCode
        )).data;
      } else throw new Error('请先保存草稿');
      message.success('已发布不可变版本；仍需显式切换并启用');
      setPublishOpen(false);
      publishForm.resetFields();
      if (target && targetSequence === documentSequence.current) {
        void loadVersionHistory(target);
        await loadImmutableDocument(target, String(publishedVersion.id), publishedVersion);
      }
    } catch (cause: any) {
      if (!cause?.errorFields) {
        const text = errorText(cause, '发布失败');
        publishForm.setFields([{ name: 'versionCode', errors: [text] }]);
        showAuthoringError(cause, '发布失败');
      }
    } finally {
      publishingRef.current = false;
      setPublishing(false);
    }
  };

  const activateViewedVersion = async () => {
    if (!documentTarget || viewVersionId === 'draft') return;
    if (!requireAssetContext('切换当前版本')) return;
    const target = documentTarget;
    const targetSequence = documentSequence.current;
    try {
      if (documentTarget.assetKind === 'agent') {
        if (!selectedAgent || String(selectedAgent.id) !== String(documentTarget.assetId)) {
          throw new Error('当前 Agent 身份与已查看版本不匹配');
        }
        await activateRuntimeAgentVersion(selectedAgent.id, viewVersionId, selectedAgent.currentVersionId);
      } else {
        if (!selectedSkill || String(selectedSkill.id) !== String(documentTarget.assetId)) {
          throw new Error('当前 Skill 身份与已查看版本不匹配');
        }
        await activateSkillVersion(selectedSkill.id, viewVersionId, selectedSkill.currentVersionId ?? null);
      }
      message.success('当前执行版本已切换');
      await loadCatalog(true);
      if (targetSequence === documentSequence.current) await loadVersionHistory(target);
    } catch (cause: any) {
      showAuthoringError(cause, '版本切换失败');
    }
  };

  const toggleAssetStatus = async () => {
    if (!documentTarget || !requireAssetContext('更新启停状态')) return;
    const agent = selectedAgent;
    const skill = selectedSkill;
    try {
      if (documentTarget.assetKind === 'agent' && agent
        && String(agent.id) === String(documentTarget.assetId)) {
        await setRuntimeAgentStatus(agent.id, agent.status === '0' ? '1' : '0');
      } else if (documentTarget.assetKind === 'skill' && skill
        && String(skill.id) === String(documentTarget.assetId)) {
        await updateSkillStatus(skill.id, skill.status === '0' ? '1' : '0');
      } else throw new Error('当前身份与已打开内容不匹配');
      message.success((agent || skill)?.status === '0' ? '已停用' : '已启用');
      await loadCatalog(true);
    } catch (cause: any) {
      showAuthoringError(cause, '状态更新失败');
    }
  };

  const openCreate = (kind: AssetKind) => {
    setCreateKind(kind);
    createForm.resetFields();
    createForm.setFieldsValue(
      kind === 'agent' ? { visibility: 'PRIVATE' } : { visibility: 'PRIVATE', invocationScope: 'ENTRYPOINT' }
    );
  };

  const createAsset = async () => {
    setCreating(true);
    try {
      const values = await createForm.validateFields();
      if (createKind === 'agent')
        await createRuntimeAgent({
          agentCode: values.code,
          name: values.name,
          description: values.description,
          visibility: values.visibility
        });
      else
        await createSkill({
          skillCode: values.code,
          name: values.name,
          description: values.description,
          visibility: 'PRIVATE',
          invocationScope: values.invocationScope
        });
      message.success(`${createKind === 'agent' ? 'Agent' : 'Skill'} 已创建并保持停用`);
      setCreateKind(undefined);
      await loadCatalog(true);
    } catch (cause: any) {
      if (!cause?.errorFields) showAuthoringError(cause, '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const identityBusy = identitySaving || identityCoverUploading || identitySyncing;

  const setIdentityFields = (agent?: RuntimeAgentSummary, skill?: AdminSkillSummary) => {
    if (agent) {
      identityForm.setFieldsValue({
        name: agent.name,
        description: agent.description,
        iconUrl: agent.iconUrl,
        visibility: agent.visibility
      });
    } else if (skill) {
      identityForm.setFieldsValue({
        name: skill.name,
        description: skill.description,
        capabilityDescription: skill.capabilityDescription,
        iconUrl: skill.iconUrl,
        visibility: skill.visibility,
        invocationScope: skill.invocationScope,
        cacheToDisk: skill.cacheToDisk
      });
    }
  };

  const openIdentity = () => {
    if (!requireAssetContext('编辑身份信息') || (!selectedAgent && !selectedSkill)) return;
    identitySavingRef.current = false;
    identityCoverUploadingRef.current = false;
    identitySyncingRef.current = false;
    setIdentityCoverUploading(false);
    setIdentitySyncing(false);
    setIdentityFields(selectedAgent, selectedSkill);
    setIdentityOpen(true);
  };

  const closeIdentity = () => {
    if (identitySavingRef.current || identityCoverUploadingRef.current || identitySyncingRef.current) {
      message.warning('身份信息或封面正在保存，请稍候');
      return;
    }
    setIdentityOpen(false);
  };

  const saveIdentity = async () => {
    if (identitySavingRef.current) return;
    if (identityCoverUploadingRef.current || identitySyncingRef.current) {
      message.warning('封面正在上传或同步，请稍候再保存身份信息');
      return;
    }
    const agent = selectedAgent;
    const skill = selectedSkill;
    if (!requireAssetContext('保存身份信息') || (!agent && !skill)) return;
    identitySavingRef.current = true;
    setIdentitySaving(true);
    try {
      const values = await identityForm.validateFields();
      if (agent) {
        await editRuntimeAgentIdentity({
          id: agent.id,
          name: values.name,
          description: values.description,
          iconUrl: values.iconUrl,
          visibility: values.visibility
        });
      } else if (skill) {
        await editSkillIdentity({
          id: skill.id,
          name: values.name,
          description: values.description,
          capabilityDescription: values.capabilityDescription,
          iconUrl: values.iconUrl,
          visibility: values.visibility,
          invocationScope: values.invocationScope,
          cacheToDisk: skill.ownerType === 'PLATFORM'
            ? values.cacheToDisk : false,
          status: skill.status
        });
      }
      const catalog = await loadCatalog(true);
      const authoritativeAgent = agent && catalog?.agents?.find((item) => String(item.id) === String(agent.id));
      const authoritativeSkill = skill && catalog?.skills?.find((item) => String(item.id) === String(skill.id));
      setIdentityFields(authoritativeAgent, authoritativeSkill);
      setIdentityOpen(false);
      message.success('身份信息已保存');
    } catch (cause: any) {
      if (!cause?.errorFields) showAuthoringError(cause, '身份保存失败');
    } finally {
      identitySavingRef.current = false;
      setIdentitySaving(false);
    }
  };

  const openReferenceEditor = () => {
    if (!requireAssetContext('编辑参考文件')) return;
    if (!documentTarget
      || (documentTarget.documentKind !== 'agent-reference'
        && documentTarget.documentKind !== 'skill-reference')) return;
    const resources = documentTarget.assetKind === 'agent' ? agentDraft?.resources : skillDraft?.resources;
    if (!resources) return;
    try {
      const index = findUniqueReferenceIndex(resources, documentTarget.path);
      const resource = resources[index];
      referenceDialogSequence.current += 1;
      setReferenceEditingPath(documentTarget.path);
      referenceForm.setFieldsValue({ resourceKey: resource.resourceKey, filePath: resource.filePath });
      setReferenceOpen(true);
    } catch (cause: any) {
      showAuthoringError(cause, '当前参考文件无法安全定位');
    }
  };

  const saveReference = async () => {
    if (savingRef.current || publishingRef.current || readonlyVersion || documentLoading || documentError) return;
    if (!requireAssetContext('保存参考文件')) return;
    const editingAgent = Boolean(documentTarget?.assetKind === 'agent' && selectedAgent && agentDraft
      && String(documentTarget.assetId) === String(agentDraft.agentId));
    const editingSkill = Boolean(documentTarget?.assetKind === 'skill' && selectedSkill && skillDraft
      && String(documentTarget.assetId) === String(skillDraft.skillId));
    if (!editingAgent && !editingSkill) return;
    const targetSequence = documentSequence.current;
    const dialogSequence = referenceDialogSequence.current;
    const workingDraft = editingAgent ? agentDraft! : skillDraft!;
    try {
      const values = await referenceForm.validateFields();
      const latest = referenceContextRef.current;
      if (targetSequence !== documentSequence.current || dialogSequence !== referenceDialogSequence.current
        || !latest.assetContextReady || latest.readonlyVersion
        || savingRef.current || publishingRef.current
        || (editingAgent ? !latest.canAgentEdit || latest.agentDraft !== workingDraft
          : !latest.canSkillEdit || latest.skillDraft !== workingDraft)) {
        referenceForm.setFields([{ name: 'filePath', errors: ['当前草稿或权限已变化，请重新打开参考文件后操作'] }]);
        return;
      }
      const currentResources = editingAgent ? agentDraft!.resources : skillDraft!.resources;
      validateReferencePaths(currentResources, '当前草稿');
      const editingIndex = referenceEditingPath == null
        ? undefined
        : findUniqueReferenceIndex(currentResources, referenceEditingPath);
      const duplicateKey = currentResources.some(
        (resource, index) => index !== editingIndex && resource.resourceKey === values.resourceKey
      );
      const duplicatePath = currentResources.some(
        (resource, index) => index !== editingIndex && resource.filePath === values.filePath
      );
      if (duplicateKey || duplicatePath) {
        if (duplicateKey) referenceForm.setFields([{ name: 'resourceKey', errors: ['资源键已存在'] }]);
        if (duplicatePath) referenceForm.setFields([{ name: 'filePath', errors: ['逻辑文件路径已存在'] }]);
        return;
      }
      const previous = editingIndex == null ? undefined : currentResources[editingIndex];
      const common = {
        ...previous,
        resourceKey: values.resourceKey,
        filePath: values.filePath,
        mimeType: previous?.mimeType || (values.filePath.endsWith('.txt') ? 'text/plain' : 'text/markdown'),
        content: previous?.content ?? '',
        routeJson: previous?.routeJson ?? '{}'
      };
      const assetKind = editingAgent ? 'agent' : 'skill';
      const documents = previous && previous.filePath !== common.filePath
        ? renamePackageDocument(packageDocuments(assetKind, workingDraft), previous.filePath, common.filePath)
        : packageDocuments(assetKind, workingDraft);
      let nextDocument: RuntimeAgentDraft | SkillDraftDetail;
      if (editingAgent) {
        const next = common as RuntimeAgentPackageResource;
        const resources = [...agentDraft!.resources];
        if (editingIndex == null) resources.push(next);
        else resources[editingIndex] = next;
        nextDocument = rewrittenDraft('agent', { ...agentDraft!, resources }, documents);
        setAgentDraft(nextDocument);
      } else {
        const next = { ...common, resourceType: (previous as any)?.resourceType || 'REFERENCE' };
        const resources = [...skillDraft!.resources];
        if (editingIndex == null) resources.push(next);
        else resources[editingIndex] = next;
        nextDocument = rewrittenDraft('skill', { ...skillDraft!, resources }, documents);
        setSkillDraft(nextDocument);
      }
      setReferenceOpen(false);
      referenceDialogSequence.current += 1;
      setReferenceEditingPath(undefined);
      setDirty(true);
      setValidation(undefined);
      setReferenceBlockers([]);
      referenceForm.resetFields();
      const assetId = documentTarget!.assetId;
      const target: DocumentTarget = {
        key: referenceDocumentKey(assetKind, assetId, common.filePath),
        assetKind,
        assetId,
        documentKind: editingAgent ? 'agent-reference' : 'skill-reference',
        path: common.filePath
      };
      documentSequence.current += 1;
      setDocumentTarget(target);
      setSelectedKey(target.key);
      setEditorValue(readTargetContent(target, nextDocument));
    } catch (cause: any) {
      if (!cause?.errorFields) referenceForm.setFields([{ name: 'filePath', errors: [errorText(cause, '参考文件保存失败')] }]);
    }
  };

  const deleteReference = (confirmed = false) => {
    if (savingRef.current || publishingRef.current || readonlyVersion || documentLoading || documentError) return;
    if (!requireAssetContext('删除参考文件')) return;
    if (!documentTarget
      || (documentTarget.documentKind !== 'agent-reference'
        && documentTarget.documentKind !== 'skill-reference')) return;
    const isAgent = documentTarget.assetKind === 'agent';
    if (isAgent ? !canAgentEdit : !canSkillEdit) return;
    const draft = isAgent ? agentDraft : skillDraft;
    if (!draft) return;
    try {
      findUniqueReferenceIndex(draft.resources, documentTarget.path);
      const blockers = collectPackageDocumentReferences(packageDocuments(documentTarget.assetKind, draft))
        .filter((reference) => reference.sourcePath !== documentTarget.path
          && (reference.invalidReason || reference.targetPath === documentTarget.path));
      setReferenceBlockers(blockers);
      if (blockers.length) return;
      if (!confirmed) {
        const expectedTarget = documentTarget;
        const expectedSequence = documentSequence.current;
        Modal.confirm({
          title: '从当前草稿删除这个参考文件？',
          content: documentTarget.path,
          okText: '删除文件',
          okButtonProps: { danger: true },
          cancelText: '取消',
          onOk: () => {
            const latest = referenceContextRef.current;
            if (documentSequence.current !== expectedSequence || latest.documentTarget !== expectedTarget
              || (isAgent ? latest.agentDraft : latest.skillDraft) !== draft
              || !latest.assetContextReady || latest.readonlyVersion
              || savingRef.current || publishingRef.current
              || (isAgent ? !latest.canAgentEdit : !latest.canSkillEdit)) {
              message.warning('当前草稿或权限已变化，请重新确认删除');
              return;
            }
            deleteReference(true);
          }
        });
        return;
      }
      if (isAgent) {
        const agent = draft as RuntimeAgentDraft;
        const index = findUniqueReferenceIndex(agent.resources, documentTarget.path);
        setAgentDraft({
          ...agent,
          resources: agent.resources.filter((_, resourceIndex) => resourceIndex !== index)
        });
      } else {
        const skill = draft as SkillDraftDetail;
        const index = findUniqueReferenceIndex(skill.resources, documentTarget.path);
        setSkillDraft({
          ...skill,
          resources: skill.resources.filter((_, resourceIndex) => resourceIndex !== index)
        });
      }
    } catch (cause: any) {
      showAuthoringError(cause, '当前参考文件无法安全定位');
      return;
    }
    setDirty(true);
    setValidation(undefined);
    const target = entryDocumentTarget(
      isAgent ? 'agent' : 'skill',
      isAgent ? (draft as RuntimeAgentDraft).agentId : (draft as SkillDraftDetail).skillId
    );
    documentSequence.current += 1;
    setDocumentTarget(target);
    setSelectedKey(target.key);
    setEditorValue(readTargetContent(target, draft));
  };

  const deleteSkill = async () => {
    if (!requireAssetContext('删除 Skill') || !selectedSkill) return;
    if (dirty) {
      message.warning('请先保存或复制当前未保存内容，再删除 Skill');
      return;
    }
    try {
      await removeSkill(selectedSkill.id);
      message.success('Skill 已软删除');
      setSelectedKey(undefined);
      setDocumentTarget(undefined);
      await loadCatalog(true);
    } catch (cause: any) {
      if (!showReferenceConflict(cause, 'Skill 仍有有效引用')) showAuthoringError(cause, '删除失败');
    }
  };

  const deleteAgent = async () => {
    if (!requireAssetContext('删除 Agent') || !selectedAgent) return;
    if (dirty) {
      message.warning('请先保存或复制当前未保存内容，再删除 Agent');
      return;
    }
    try {
      await removeRuntimeAgent(selectedAgent.id);
      message.success('Agent 已软删除');
      setSelectedKey(undefined);
      setDocumentTarget(undefined);
      await loadCatalog(true);
    } catch (cause: any) {
      showAuthoringError(cause, '删除失败');
    }
  };

  const restoreDeletedIdentity = async (kind: AssetKind, id: RuntimeId | SkillId) => {
    const key = `${kind}:${String(id)}`;
    setRestoringKey(key);
    try {
      if (kind === 'agent') await restoreRuntimeAgent(id);
      else await restoreSkill(id);
      message.success(`${kind === 'agent' ? 'Agent' : 'Skill'} 已恢复并保持停用`);
      await loadCatalog(true);
    } catch (cause: any) {
      showAuthoringError(cause, '恢复失败');
    } finally {
      setRestoringKey('');
    }
  };

  const moderateSkill = async () => {
    if (!requireAssetContext('下架 Skill') || !selectedSkill) return;
    try {
      const { reason } = await moderationForm.validateFields();
      await unpublishUserSkill(selectedSkill.id, reason.trim());
      setModerationOpen(false);
      moderationForm.resetFields();
      message.success('用户 Skill 已转为私有并记录原因');
      await loadCatalog(true);
    } catch (cause: any) {
      if (!cause?.errorFields) showAuthoringError(cause, '下架失败');
    }
  };

  const changePublicationSetting = async (checked: boolean) => {
    setSettingsSaving(true);
    try {
      await saveSkillPublicationSettings({ allowUserPublicSkills: checked });
      setAllowUserPublicSkills(checked);
      message.success('用户公开 Skill 策略已保存');
    } catch (cause: any) {
      showAuthoringError(cause, '策略保存失败');
    } finally {
      setSettingsSaving(false);
    }
  };

  const closeAdvanced = () => {
    advancedSequence.current += 1;
    advancedTargetId.current = undefined;
    setAdvancedCatalog(undefined);
    setAdvancedError('');
    setAdvancedLoading(false);
    setAdvancedOpen(false);
  };

  const loadAdvancedCatalog = async (targetId: RuntimeId) => {
    const sequence = ++advancedSequence.current;
    advancedTargetId.current = targetId;
    setAdvancedCatalog(undefined);
    setAdvancedLoading(true);
    setAdvancedError('');
    try {
      const [modelsResponse, skillsResponse, toolsResponse] = await Promise.all([
        listRuntimeAgentModels(),
        listRuntimeAgentSkills(),
        listRuntimeAgentTools()
      ]);
      if (sequence !== advancedSequence.current || String(advancedTargetId.current) !== String(targetId)) return;
      if (!Array.isArray(modelsResponse.data)
        || !Array.isArray(skillsResponse.data)
        || !Array.isArray(toolsResponse.data)) {
        throw new Error('模型、Skill 或 Tool 目录响应格式无效');
      }
      const modelCodes = new Set<string>();
      modelsResponse.data.forEach((item) => {
        if (modelCodes.has(item.modelCode)) throw new Error(`模型目录包含重复编码：${item.modelCode}`);
        modelCodes.add(item.modelCode);
      });
      const emptyBindingResolution = resolveRuntimeAgentBindings([], [], skillsResponse.data, toolsResponse.data);
      if (emptyBindingResolution.duplicateCatalogSkillVersionIds.length > 0
        || emptyBindingResolution.duplicateCatalogToolCodes.length > 0
        || emptyBindingResolution.invalidCatalogSkillEntries > 0
        || emptyBindingResolution.invalidCatalogToolEntries > 0) {
        throw new Error('Skill 或 Tool 目录包含重复或空的 ID / 编码');
      }
      setAdvancedCatalog({
        targetId,
        sequence,
        models: modelsResponse.data,
        skills: skillsResponse.data,
        tools: toolsResponse.data
      });
    } catch (cause: any) {
      if (sequence === advancedSequence.current && String(advancedTargetId.current) === String(targetId)) {
        setAdvancedCatalog(undefined);
        setAdvancedError(errorText(cause, '模型、Skill 或 Tool 目录尚未就绪'));
      }
    } finally {
      if (sequence === advancedSequence.current && String(advancedTargetId.current) === String(targetId)) {
        setAdvancedLoading(false);
      }
    }
  };

  const openAdvanced = () => {
    if (savingRef.current || !requireAssetContext('编辑 Agent 高级配置') || !agentDraft || !selectedAgent) return;
    const targetId = selectedAgent.id;
    setAdvancedOpen(true);
    agentAdvancedForm.setFieldsValue({
      ...agentDraft,
      responsePolicy: normalizeRuntimeAgentResponsePolicy(agentDraft.responsePolicy, agentDraft.protocolVersion),
      selectedSkillVersionIds: (agentDraft.skillBindings || []).map((item) => String(item.skillVersionId)),
      selectedToolCodes: (agentDraft.toolBindings || []).map((item) => item.toolCode)
    });
    void loadAdvancedCatalog(targetId);
  };

  const retryAdvancedCatalog = () => {
    if (!requireAssetContext('重试高级配置目录') || !selectedAgent || documentTarget?.assetKind !== 'agent'
      || String(selectedAgent.id) !== String(documentTarget.assetId)) return;
    void loadAdvancedCatalog(selectedAgent.id);
  };

  const applyAdvanced = async () => {
    if (savingRef.current) return;
    if (!requireAssetContext('应用 Agent 高级配置')) return;
    if (!agentDraft || !selectedAgent
      || String(advancedTargetId.current) !== String(selectedAgent.id)) return;
    const catalog = advancedCatalog;
    if (!advancedCatalogReady || !catalog) {
      message.warning(advancedError || '请先完成当前 Agent 的模型、Skill 与 Tool 目录读取');
      return;
    }
    try {
      const values = await agentAdvancedForm.validateFields();
      if (catalog.sequence !== advancedSequence.current
        || String(catalog.targetId) !== String(advancedTargetId.current)
        || String(catalog.targetId) !== String(selectedAgent.id)
        || documentTarget?.assetKind !== 'agent'
        || String(catalog.targetId) !== String(documentTarget.assetId)) {
        message.warning('高级配置目录已失效，请重新打开或重试目录');
        return;
      }
      if (!values.selectableModelCodes.includes(values.defaultModelCode)) throw new Error('默认模型必须属于允许模型');
      const { selectedSkillVersionIds, selectedToolCodes, ...draftValues } = values;
      const bindingResolution = resolveRuntimeAgentBindings(
        selectedSkillVersionIds || [],
        selectedToolCodes || [],
        catalog.skills,
        catalog.tools
      );
      if (!bindingResolution.complete) {
        message.warning('请先在选择框中显式移除全部不可用历史绑定');
        return;
      }
      setAgentDraft({
        ...agentDraft,
        ...draftValues,
        responsePolicy: normalizeRuntimeAgentResponsePolicy({
          ...agentDraft.responsePolicy,
          ...values.responsePolicy
        }, values.protocolVersion),
        skillBindings: bindingResolution.skillBindings,
        toolBindings: bindingResolution.toolBindings
      });
      closeAdvanced();
      setDirty(true);
      setValidation(undefined);
    } catch (cause: any) {
      if (!cause?.errorFields) showAuthoringError(cause, '高级配置无效');
    }
  };

  useEffect(() => {
    if (!advancedOpen || advancedTargetId.current == null) return;
    const targetMatches = documentTarget?.assetKind === 'agent'
      && String(documentTarget.assetId) === String(advancedTargetId.current)
      && String(selectedAgent?.id) === String(advancedTargetId.current)
      && String(agentDraft?.agentId) === String(advancedTargetId.current);
    if (targetMatches && assetContextReady) return;
    advancedSequence.current += 1;
    advancedTargetId.current = undefined;
    setAdvancedCatalog(undefined);
    setAdvancedLoading(false);
    setAdvancedError(assetContextIssue || '当前 Agent 已变化，请关闭高级配置后重新打开');
  }, [
    advancedOpen,
    agentDraft?.agentId,
    assetContextIssue,
    assetContextReady,
    documentTarget?.assetId,
    documentTarget?.assetKind,
    selectedAgent?.id
  ]);

  const canEditCurrent = assetContextReady
    && (selectedAgent ? canAgentEdit : selectedSkill ? canSkillEdit : false);
  const editorUnavailable = saving || publishing || documentLoading || !!documentError
    || !assetContextReady
    || !(documentTarget?.assetKind === 'agent' ? canAgentEdit : canSkillEdit);
  const referenceEditable = !editorUnavailable && !readonlyVersion;
  const overview = !!selectedSkill && documentTarget?.key === 'skill:' + selectedSkill.id;
  const referenceFolder = !!selectedSkill && documentTarget?.key === 'skill:' + selectedSkill.id + ':references';
  const currentVersionId = selectedAgent?.currentVersionId ?? selectedSkill?.currentVersionId;
  const currentDraftDigest = documentTarget?.assetKind === 'agent' ? agentDraft?.draftDigest : skillDraft?.draftDigest;
  const publishReady = !dirty && !!validation?.valid && !!validation.draftDigest
    && validation.draftDigest === currentDraftDigest;
  const displayedVersions = [...versions];
  if (immutableDocument
    && String(immutableDocument.assetId) === String(documentTarget?.assetId)
    && !displayedVersions.some((version) => String(version.id) === String(immutableDocument.data.id))) {
    displayedVersions.unshift(immutableDocument.data);
  }
  const versionOptions = [
    { value: 'draft', label: '草稿（可编辑）' },
    ...displayedVersions.map((version) => ({
      value: String(version.id),
      label: `${version.versionCode}${version.current ? ' · 当前' : ''}`
    }))
  ];

  return (
    <div className="app-container skill-authoring" style={{
      '--skill-accent': token.colorPrimary, '--skill-tint': token.colorPrimaryBg,
      '--skill-line': token.colorBorderSecondary, '--skill-muted': token.colorTextSecondary,
      '--skill-panel': token.colorBgContainer, '--skill-fill': token.colorFillQuaternary,
      '--skill-text': token.colorText, '--skill-success': token.colorSuccessText
    } as React.CSSProperties}>
      <PageHeader title={scope === 'skill' ? 'Skill 管理' : authoringTitle(scope)}
        desc={undefined} />
      <div className="skill-authoring__toolbar">
        <Space wrap>
          <Input.Search
            allowClear
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onSearch={value => { if (value.trim() !== deferredKeyword) changeDirectory(() => setSearchKeyword(value.trim())); }}
            placeholder={scope === 'agent' ? '搜索助手 Agent' : scope === 'skill' ? '搜索平台或用户 Skill' : '搜索 Agent、平台 Skill 或用户 Skill'}
            className="skill-authoring__search"
          />
          {scope !== 'agent' && <Radio.Group optionType="button" buttonStyle="solid" value={skillOwnerType || 'ALL'}
            options={[{ label: '全部', value: 'ALL' }, { label: '平台 Skill', value: 'PLATFORM' }, { label: '用户 Skill', value: 'USER' }]}
            onChange={event => {
              const owner = event.target.value;
              changeDirectory(() => {
                setSkillOwnerType(owner === 'ALL' ? undefined : owner);
                setSkillOwnerUserId(''); setOwnerFilter('');
              });
            }} />}
          {scope === 'skill' && <Select aria-label="状态" allowClear placeholder="状态" style={{ width: 105 }} value={skillStatus} onChange={value => changeDirectory(() => setSkillStatus(value))}
            options={[{ label: '已启用', value: '0' }, { label: '已停用', value: '1' }]} />}
          {skillOwnerType === 'USER' && <Input.Search
            onSearch={value => { if (value !== deferredOwnerUserId) changeDirectory(() => setOwnerFilter(value)); }}
            allowClear
            value={skillOwnerUserId}
            placeholder="用户 ID（保持字符串）"
            onChange={(event) => setSkillOwnerUserId(event.target.value.replace(/\D/g, ''))}
            className="skill-authoring__owner-id"
          />}
          <Space size={6}>
            <Text type="secondary">显示已删除</Text>
            <Switch size="small" checked={showDeleted} onChange={value => changeDirectory(() => setShowDeleted(value))} />
          </Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void loadCatalog(true)}>
            刷新目录
          </Button>
          {onRuns && <Button onClick={() => onRuns()}>全部运行审计</Button>}
          {canAgentEdit && (
            <Button icon={<PlusOutlined />} onClick={() => openCreate('agent')}>
              新建 Agent
            </Button>
          )}
          {canSkillEdit && (
            <Button type="primary" icon={<PlusOutlined />} onClick={onCreateSkill || (() => openCreate('skill'))}>
              新建 Skill
            </Button>
          )}
        </Space>
        {canSkillQuery && (
          <Space className="skill-authoring__publication-setting">
            <Text>允许用户公开 Skill</Text>
            {allowUserPublicSkills == null ? (
              <Tooltip title="配置接口未就绪或无读取权限">
                <Tag>未就绪</Tag>
              </Tooltip>
            ) : (
              <Switch
                checked={allowUserPublicSkills}
                disabled={!canSkillEdit || settingsSaving}
                loading={settingsSaving}
                onChange={(checked) => void changePublicationSetting(checked)}
              />
            )}
          </Space>
        )}
      </div>
      {!canAgentList && !canSkillList && <Alert type="warning" showIcon
        message="没有 Agent 或 Skill 编制目录权限" description="请为当前角色分配对应列表与详情权限。" />}
      {loadError && <Alert type="error" showIcon message="目录读取失败" description={loadError} />}
      {locatedSkill && !documentTarget && catalogReady && !loading && !loadError &&
        !targetMap.has('skill:' + locatedSkill + ':main') &&
        <Alert type="warning" showIcon message="在当前目录中找不到此 Skill" />}
      <div className="skill-authoring__workspace">
        <Card size="small" className="skill-authoring__tree-card" styles={{ body: { padding: 8 } }}>
          <Spin spinning={loading}>
            <Tree
              showIcon
              blockNode
              expandedKeys={expanded}
              onExpand={setExpanded}
              treeData={treeData}
              selectedKeys={selectedKey ? [selectedKey] : []}
              onSelect={(keys) => {
                const target = targetMap.get(String(keys[0] || ''));
                if (target) {
                  setExpanded(current => [...new Set([...current, target.assetKind + ':' + target.assetId])]);
                  requestSelect(target);
                }
              }}
            />
            {locatedSkill && <Tag closable onClose={event => { event.preventDefault(); changeDirectory(() => {}); }}>已定位指定 Skill</Tag>}
            {!loading && activeAgents.length === 0 && activeSkills.length === 0 && (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={skillOwnerType === 'USER' ? '没有符合条件的用户 Skill' : deferredKeyword ? '没有匹配的活动身份' : '活动目录为空'} />
            )}
            {showDeleted && (deletedAgents.length > 0 || deletedSkills.length > 0) && (
              <Card size="small" title={`已删除身份（${deletedAgents.length + deletedSkills.length}）`}
                styles={{ body: { padding: 8 } }} style={{ marginTop: 8 }}>
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  {deletedAgents.map((agent) => {
                    const key = `agent:${String(agent.id)}`;
                    return <Space key={key} wrap style={{ justifyContent: 'space-between', width: '100%' }}>
                      <Space size={6} wrap>
                        <Tag color="red">Agent 已删除</Tag>
                        <Text>{agent.name}</Text>
                        <Text type="secondary">{agent.agentCode}</Text>
                      </Space>
                      <Button size="small" disabled={!canAgentEdit}
                        loading={restoringKey === key}
                        onClick={() => void restoreDeletedIdentity('agent', agent.id)}>
                        恢复
                      </Button>
                    </Space>;
                  })}
                  {deletedSkills.map((skill) => {
                    const key = `skill:${String(skill.id)}`;
                    return <Space key={key} wrap style={{ justifyContent: 'space-between', width: '100%' }}>
                      <Space size={6} wrap>
                        <Tag color="red">Skill 已删除</Tag>
                        <Text>{skill.name}</Text>
                        <Text type="secondary">{skill.skillCode}</Text>
                      </Space>
                      <Button size="small" disabled={!canSkillRestore}
                        loading={restoringKey === key}
                        onClick={() => void restoreDeletedIdentity('skill', skill.id)}>
                        恢复
                      </Button>
                    </Space>;
                  })}
                </Space>
              </Card>
            )}
          </Spin>
          {canSkillList && skillTotal > skillPageSize && <Pagination size="small" simple showSizeChanger={false}
            aria-label="Skill 目录分页" current={skillPage} pageSize={skillPageSize} total={skillTotal}
            onChange={page => { if (page !== skillPage) changeDirectory(() => {}, page); }} />}
        </Card>
        <Card size="small" className={'skill-authoring__editor-card' + (overview ? ' skill-summary' : '')} styles={{ body: { padding: 0 } }}>
          {!documentTarget ? (
            <Empty className="skill-authoring__empty" description="请从左侧选择要管理的条目" />
          ) : (
            <>
              <header className="skill-authoring__editor-header">
                {overview && <span className="skill-summary__mark" aria-hidden="true"><AppstoreOutlined /></span>}
                <div className="skill-authoring__file-title">
                  <Text strong ellipsis={{ tooltip: documentTarget.path }}>
                    {overview ? selectedSkill?.name : referenceFolder ? '参考文件' : documentTarget.path}
                  </Text>
                  <Space size={6} wrap>
                    {overview && selectedSkill ? <span className="skill-summary__status" data-enabled={selectedSkill.status === '0'}>
                      {selectedSkill.status === '0' ? '已启用' : '已停用'}
                    </span> : <Tag>{documentTarget.assetKind === 'agent' ? 'Agent' : 'Skill'}</Tag>}
                    {currentAsset && (
                      <Tooltip title={(currentAsset as any).agentCode || (currentAsset as any).skillCode}>
                        <Text type="secondary" className="skill-authoring__code">
                          {(currentAsset as any).agentCode || (currentAsset as any).skillCode}
                        </Text>
                      </Tooltip>
                    )}
                    {selectedSkill?.ownerType === 'USER' && (
                      <Tag color="purple">用户 #{selectedSkill.ownerUserId == null ? '未知' : String(selectedSkill.ownerUserId)}</Tag>
                    )}
                    {selectedSkill?.moderationReason && (
                      <Tooltip title={`${selectedSkill.moderationReason}${selectedSkill.moderatedBy
                        ? ` · 管理员 ${selectedSkill.moderatedBy}` : ''}${selectedSkill.moderatedAt
                        ? ` · ${selectedSkill.moderatedAt}` : ''}`}>
                        <Tag color="orange">有下架审计</Tag>
                      </Tooltip>
                    )}
                    {dirty && <Tag color="warning">未保存</Tag>}
                  </Space>
                  {overview && selectedSkill?.description && <p className="skill-summary__description">{selectedSkill.description}</p>}
                </div>
                <Space wrap className="skill-authoring__editor-actions">
                  <Select
                    aria-label="查看草稿或版本"
                    value={viewVersionId}
                    options={versionOptions}
                    className="skill-authoring__version-select"
                    loading={versionsLoading}
                    disabled={saving || publishing || documentLoading || !assetContextReady}
                    onChange={requestVersionChange}
                  />
                  {readonlyVersion && assetContextReady && String(viewVersionId) !== String(currentVersionId) && (
                    <Button
                      disabled={saving || documentLoading
                        || (documentTarget.assetKind === 'agent' ? !canAgentActivate : !canSkillEdit)}
                      onClick={() => void activateViewedVersion()}
                    >
                      切换为当前版本
                    </Button>
                  )}
                  {!readonlyVersion && (
                    <Button
                      icon={<SaveOutlined />}
                      type="primary"
                      loading={saving}
                      disabled={editorUnavailable || !dirty}
                      onClick={() => void saveDraft()}
                    >
                      保存草稿
                    </Button>
                  )}
                  {!readonlyVersion && (
                    <Button
                      icon={<CheckCircleOutlined />}
                      loading={validating}
                      disabled={editorUnavailable}
                      onClick={() => void validateDraft()}
                    >
                      校验
                    </Button>
                  )}
              {!readonlyVersion && (!selectedSkill || overview) && <Tooltip title={assetContextIssue
                || (publishReady ? undefined : '请先保存草稿，并对当前摘要完成服务端校验')}>
                <span><Button
                    icon={<RocketOutlined />}
                    disabled={!assetContextReady || !publishReady
                      || (documentTarget.assetKind === 'agent' ? !canAgentPublish : !canSkillEdit)}
                    onClick={() => {
                      publishForm.setFieldsValue({ versionCode: documentTarget?.assetKind === 'agent'
                        ? agentDraft?.packageVersionCode || suggestVersion(versions) : suggestVersion(versions) });
                      setPublishOpen(true);
                    }}
                  >
                    发布
                  </Button></span>
              </Tooltip>}
                </Space>
              </header>
              {assetContextIssue && (
                <Alert
                  type="warning"
                  showIcon
                  message="当前身份状态不可用"
                  description={`${assetContextIssue}${dirty ? ' 未保存的本地工作稿仍保留在编辑器中。' : ''}`}
                />
              )}
              {!readonlyVersion && assetContextReady && documentTarget.assetKind === 'agent'
                && agentDraft?.packageVersionCode && (
                <Alert
                  type="info"
                  showIcon
                  message={`当前草稿固定为内置包版本 ${agentDraft.packageVersionCode}`}
                  description="按原版本发布时保持固定版本。需要发布下一版本时，请先显式转换；转换会保存当前全部草稿内容，解除版本锁，并使旧校验结果失效。"
                  action={<Button
                    size="small"
                    loading={saving}
                    disabled={editorUnavailable}
                    onClick={requestConvertAgentToFollowupVersion}
                  >转为后续版本</Button>}
                />
              )}
              <div className="skill-authoring__asset-bar">
                <Space wrap>
                {(!selectedSkill || overview) && <>
                <Button size="small" icon={<SettingOutlined />} disabled={!canEditCurrent || saving} onClick={openIdentity}>
                    基本信息
                  </Button>
                  {selectedSkill && onRuns && <Button size="small" onClick={() => onRuns(selectedSkill.id)}>运行记录</Button>}
                  {selectedSkill && (
                    <Button size="small" disabled={!assetContextReady || saving || publishing} onClick={openSkillPackage}>
                      参数、样例与版本管理
                    </Button>
                  )}
                  {selectedAgent && (
                  <Button size="small" disabled={!assetContextReady || !canAgentEdit || saving || readonlyVersion || !agentDraft}
                    onClick={() => void openAdvanced()}>
                      模型、Schema 与绑定
                    </Button>
                  )}
              {selectedAgent && <>
              <Tooltip title={assetContextIssue || (currentAsset?.status === '1' && currentVersionId == null
                ? '请先发布并切换一个当前版本' : undefined)}>
                <span><Button size="small" disabled={!canEditCurrent
                  || saving || (currentAsset?.status === '1' && currentVersionId == null)}
                  onClick={() => void toggleAssetStatus()}>
                  {currentAsset?.status === '0' ? '停用' : currentAsset ? '启用' : '状态不可用'}
                </Button></span>
              </Tooltip>
              </>}
                </>}
              {!readonlyVersion && (!selectedSkill || overview || referenceFolder) && assetContextReady && ((selectedSkill && canSkillEdit && skillDraft)
                || (selectedAgent && canAgentEdit && agentDraft)) && (
                <Button
                  size="small"
                  icon={<PlusOutlined />}
                  disabled={saving}
                  onClick={() => {
                    referenceDialogSequence.current += 1;
                    setReferenceEditingPath(undefined);
                    referenceForm.setFieldsValue({ resourceKey: '', filePath: 'references/' });
                    setReferenceOpen(true);
                  }}
                    >
                      新建参考文件
                    </Button>
                  )}
              {!readonlyVersion
                && (documentTarget.documentKind === 'skill-reference'
                  || documentTarget.documentKind === 'agent-reference')
                && canEditCurrent && (
                <Button size="small" disabled={saving} onClick={openReferenceEditor}>重命名 / 属性</Button>
              )}
              {!readonlyVersion
                && (documentTarget.documentKind === 'skill-reference'
                  || documentTarget.documentKind === 'agent-reference')
                && canEditCurrent && (
                      <Button size="small" danger disabled={saving || publishing} icon={<DeleteOutlined />}
                        onClick={() => deleteReference()}>
                        删除文件
                      </Button>
                  )}
                  {overview && assetContextReady && selectedSkill?.ownerType === 'USER'
                    && selectedSkill.visibility === 'PUBLIC' && canSkillEdit && (
                    <Button size="small" danger disabled={saving} onClick={() => setModerationOpen(true)}>
                      转为私有
                    </Button>
                  )}
                  {overview && <Popover trigger="click" placement="bottomRight" content={<Space direction="vertical">
              <Tooltip title={assetContextIssue || (currentAsset?.status === '1' && currentVersionId == null
                ? '请先发布并切换一个当前版本' : undefined)}>
                <span><Button size="small" disabled={!canEditCurrent
                  || saving || (currentAsset?.status === '1' && currentVersionId == null)}
                  onClick={() => void toggleAssetStatus()}>
                  {currentAsset?.status === '0' ? '停用' : currentAsset ? '启用' : '状态不可用'}
                </Button></span>
              </Tooltip>
                  {overview && assetContextReady && selectedSkill && canSkillRemove && (
                    <Tooltip title={dirty ? '请先保存或复制当前未保存内容，再删除 Skill' : undefined}>
                      <span>
                        <Popconfirm
                          disabled={dirty}
                          title="确认软删除此 Skill？"
                          description="存在有效引用时服务端会阻止删除并返回引用位置。"
                          onConfirm={() => void deleteSkill()}
                        >
                          <Button size="small" danger disabled={saving || dirty} icon={<DeleteOutlined />}>
                            删除 Skill
                          </Button>
                        </Popconfirm>
                      </span>
                    </Tooltip>
                  )}
                  </Space>}><Button size="small">更多操作</Button></Popover>}
                  {assetContextReady && selectedAgent && canAgentEdit && (
                    <Tooltip title={dirty ? '请先保存或复制当前未保存内容，再删除 Agent' : undefined}>
                      <span>
                        <Popconfirm
                          disabled={dirty}
                          title="确认软删除此 Agent？"
                          description="活动模块绑定、未收口运行或受理状态未知的任务存在时，服务端会拒绝删除。"
                          onConfirm={() => void deleteAgent()}
                        >
                          <Button size="small" danger disabled={saving || publishing || dirty} icon={<DeleteOutlined />}>
                            删除 Agent
                          </Button>
                        </Popconfirm>
                      </span>
                    </Tooltip>
                  )}
                </Space>
              </div>
              {selectedSkill && !overview && <Button onClick={() => { const target = targetMap.get('skill:' + selectedSkill.id); if (target) requestSelect(target); }}>返回 Skill 详情</Button>}
              {referenceBlockers.length > 0 && (
                <Alert
                  className="skill-authoring__reference-blockers"
                  type="error"
                  showIcon
                  message={referenceBlockers.some((reference) => reference.invalidReason)
                    ? '当前草稿存在无效包内链接，请修正后再删除'
                    : '参考文件仍被当前草稿引用，无法删除'}
                  description={<div className="skill-authoring__reference-locations" role="list">
                    {referenceBlockers.map((reference) => (
                      <div key={`${reference.sourcePath}:${reference.line}:${reference.column}`} role="listitem">
                        <Button type="link" size="small" disabled={saving || publishing || !assetContextReady || readonlyVersion} onClick={() => {
                          const target = [...targetMap.values()].find((item) => item.assetKind === documentTarget.assetKind
                            && String(item.assetId) === String(documentTarget.assetId) && item.path === reference.sourcePath);
                          if (!target) return;
                          requestSelect(target);
                          setReferenceJump({ assetKind: target.assetKind, assetId: target.assetId,
                            versionId: viewVersionId, path: reference.sourcePath, line: reference.line });
                        }}>{reference.sourcePath} · 第 {reference.line} 行</Button>
                        {reference.invalidReason && <Text type="danger">：{reference.invalidReason}</Text>}
                      </div>
                    ))}
                  </div>}
                />
              )}
              {versionsError && (
                <Alert
                  type="warning"
                  showIcon
                  message="不可变版本列表读取失败"
                  description={versionsError}
                  action={
                    <Button size="small" loading={versionsLoading} disabled={!assetContextReady}
                      onClick={() => void loadVersionHistory(documentTarget)}>
                      重试版本列表
                    </Button>
                  }
                />
              )}
              <Spin spinning={documentLoading} className="skill-authoring__editor-spin">
                {documentError && (
                  <Alert
                    type="error"
                    showIcon
                    message="内容未就绪"
                    description={documentError}
                    action={
                      <Button size="small" disabled={!assetContextReady}
                        onClick={() => void loadDocument(documentTarget)}>
                        重试
                      </Button>
                    }
                  />
                )}
                {overview && selectedSkill && <section className="skill-summary__body">
                  <h2>运行配置</h2>
                  <dl className="skill-summary__facts">
                    <div><dt><BranchesOutlined />生效版本</dt><dd>{versions.find(v => String(v.id) === String(currentVersionId))?.versionCode || (currentVersionId ? String(currentVersionId) : '尚未启用版本')}</dd></div>
                    <div><dt><DeploymentUnitOutlined />当前模型</dt><dd>{selectedSkill.modelCode || '未指定'}</dd></div>
                    <div><dt><TeamOutlined />所有者</dt><dd>{selectedSkill.ownerType === 'USER' ? '用户 #' + selectedSkill.ownerUserId : '平台'}</dd></div>
                  </dl>
                  <h2>工作文件</h2>
                  <div className="skill-summary__documents">
                    {[
                      { suffix: 'main', title: '使用说明', path: 'SKILL.md', icon: <FileMarkdownOutlined /> },
                      { suffix: 'system', title: '系统提示词', path: 'system_prompt.md', icon: <CodeOutlined /> },
                      { suffix: 'references', title: '参考文件', path: 'references/', icon: <FolderOpenOutlined /> }
                    ].map(file => <button type="button" key={file.suffix} onClick={() => {
                      const target = targetMap.get('skill:' + selectedSkill.id + ':' + file.suffix);
                      if (target) requestSelect(target);
                    }}>
                      <span className="skill-summary__file-icon">{file.icon}</span>
                      <span><strong>{file.title}</strong><small>{file.path}</small></span>
                      <RightOutlined />
                    </button>)}
                  </div>
                </section>}
                {referenceFolder && <Space direction="vertical" style={{ padding: 20 }}>
                  {(visibleSkillResources || []).length === 0 && <Text type="secondary">暂无参考文件</Text>}
                  {(visibleSkillResources || []).map(file => <Button key={file.filePath} type="link" onClick={() => {
                    const target = selectedSkill && targetMap.get(referenceDocumentKey('skill', selectedSkill.id, file.filePath));
                    if (target) requestSelect(target);
                  }}>{file.filePath}</Button>)}
                </Space>}
                {!overview && !referenceFolder && !documentError && (
                  <div className="skill-authoring__editor-body">
                    <Input.TextArea
                      ref={editorRef}
                      aria-label={`${documentTarget.path} 内容编辑器`}
                      value={editorValue}
                      disabled={editorUnavailable}
                      readOnly={readonlyVersion && !editorUnavailable}
                      spellCheck={false}
                      onChange={(event) => {
                        if (!readonlyVersion && !editorUnavailable) applyEditorValue(event.target.value);
                      }}
                      placeholder="在此输入文件内容"
                      className="skill-authoring__textarea"
                    />
                    <footer className="skill-authoring__editor-footer">
                      <Text type="secondary">{new TextEncoder().encode(editorValue).length.toLocaleString()} 字节</Text>
                      <Text type="secondary">{readonlyVersion ? '不可变版本 · 只读' : '草稿 · Ctrl/Cmd + S 保存'}</Text>
                    </footer>
                    {validationContent(validation)}
                  </div>
                )}
              </Spin>
            </>
          )}
        </Card>
      </div>

      <Modal
        title={`新建 ${createKind === 'agent' ? 'Agent' : 'Skill'}`}
        open={!!createKind}
        onCancel={() => setCreateKind(undefined)}
        onOk={() => void createAsset()}
        confirmLoading={creating}
        destroyOnClose
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            name="code"
            label="稳定编码"
            rules={[
              { required: true },
              createKind === 'skill'
                ? { pattern: /^[a-z][a-z0-9_]{1,63}$/, message: '以小写字母开头，2–64 位，仅可使用小写字母、数字和下划线' }
                : { pattern: /^[a-z][a-z0-9._-]{1,63}$/, message: '以小写字母开头，2–64 位，可使用小写字母、数字、点、下划线和连字符' }
            ]}
          >
            <Input autoFocus maxLength={64} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea maxLength={1000} showCount rows={3} />
          </Form.Item>
          {createKind === 'skill' && <Alert type="info" showIcon style={{ marginBottom: 16 }}
            message="Skill 会先创建为私有身份"
            description="创建后先上传绑定该 Skill ID 的真实封面；保持停用并改为公开后，首次发布会生成公开版本，再切换为当前版本并启用。" />}
        <Form.Item name="visibility" label="可见范围" rules={[{ required: true }]}>
          <Select
            disabled={createKind === 'skill'}
            options={createKind === 'skill'
              ? [{ value: 'PRIVATE', label: '私有' }]
              : [{ value: 'PRIVATE', label: '私有' }, { value: 'PUBLIC', label: '公开' }]}
          />
          </Form.Item>
          {createKind === 'skill' && (
            <Form.Item name="invocationScope" label="调用范围" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'ENTRYPOINT', label: '可直接运行' },
                  { value: 'INTERNAL', label: '仅内部调用' }
                ]}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Modal
        title="编辑稳定身份"
        open={identityOpen}
        onCancel={closeIdentity}
        onOk={() => void saveIdentity()}
        confirmLoading={identitySaving || identitySyncing}
        okButtonProps={{ disabled: !canEditCurrent || identityBusy }}
        cancelButtonProps={{ disabled: identityBusy }}
        maskClosable={!identityBusy}
        keyboard={!identityBusy}
        destroyOnClose
      >
        <Form form={identityForm} layout="vertical" disabled={!canEditCurrent || identityBusy}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} maxLength={1000} showCount />
          </Form.Item>
          {selectedSkill && (
            <Form.Item name="capabilityDescription" label="能力介绍">
              <Input.TextArea rows={3} maxLength={2000} showCount />
            </Form.Item>
          )}
          {selectedSkill ? (
            <Form.Item noStyle shouldUpdate={(before, after) => before.visibility !== after.visibility}>
              {({ getFieldValue }) => (
                <Form.Item
                  name="iconUrl"
                  label="封面"
                  extra="上传完成会立即绑定当前 Skill；公开身份必须保留服务端验证通过的封面。"
                  rules={[
                    {
                      validator: (_, value) =>
                        getFieldValue('visibility') === 'PUBLIC' && !value
                          ? Promise.reject(new Error('公开 Skill 必须上传封面'))
                          : Promise.resolve()
                    }
                  ]}
                >
                  <ImageUpload action={COVER_UPLOAD} extraData={{ skillId: String(selectedSkill.id) }}
                    name="file" maxCount={1} maxSize={5} accept="image/*"
                    disabled={identityBusy}
                    onUploadingChange={(uploading) => {
                      identityCoverUploadingRef.current = uploading;
                      setIdentityCoverUploading(uploading);
                    }}
                    onUploadSuccess={async (url) => {
                      const skillId = selectedSkill.id;
                      identitySyncingRef.current = true;
                      setIdentitySyncing(true);
                      identityForm.setFieldValue('iconUrl', url);
                      setSkills((current) => current.map((item) =>
                        String(item.id) === String(skillId) ? { ...item, iconUrl: url } : item));
                      try {
                        const catalog = await loadCatalog(true);
                        const authoritative = catalog?.skills?.find((item) => String(item.id) === String(skillId));
                        if (authoritative) identityForm.setFieldValue('iconUrl', authoritative.iconUrl);
                      } finally {
                        identitySyncingRef.current = false;
                        setIdentitySyncing(false);
                      }
                    }} />
                </Form.Item>
              )}
            </Form.Item>
          ) : (
            <Form.Item name="iconUrl" label="图标 URL">
              <Input maxLength={500} />
            </Form.Item>
          )}
          <Form.Item name="visibility" label="可见范围" rules={[{ required: true }]}
            extra={selectedSkill?.ownerType === 'USER' && selectedSkill.visibility === 'PUBLIC'
              ? '用户公开 Skill 请使用工作台中的“转为私有”操作并填写审计原因。'
              : selectedSkill?.ownerType === 'USER' && allowUserPublicSkills === false
                ? '全局“允许用户公开 Skill”当前已关闭。'
                : selectedSkill && selectedSkill.currentVersionId == null
                  ? '首次公开顺序：上传真实封面 → 保持停用并改为公开 → 发布公开版本 → 切换当前版本 → 启用。'
                : undefined}>
            <Select
              disabled={selectedSkill?.ownerType === 'USER' && selectedSkill.visibility === 'PUBLIC'}
              options={[
                { value: 'PRIVATE', label: '私有' },
                {
                  value: 'PUBLIC',
                  label: selectedSkill?.ownerType === 'USER' && allowUserPublicSkills === false
                    ? '公开（全局已关闭）'
                    : '公开',
                  disabled: selectedSkill?.ownerType === 'USER' && allowUserPublicSkills === false
                }
              ]}
            />
          </Form.Item>
          {selectedSkill && (
            <>
              <Form.Item name="invocationScope" label="调用范围" rules={[{ required: true }]}>
                <Select
                  options={[
                    { value: 'ENTRYPOINT', label: '可直接运行' },
                    { value: 'INTERNAL', label: '仅内部调用' }
                  ]}
                />
              </Form.Item>
              {selectedSkill?.ownerType === 'PLATFORM'
                ? <Form.Item name="cacheToDisk" label="缓存到本地磁盘" valuePropName="checked"><Switch /></Form.Item>
                : <Form.Item label="缓存到本地磁盘" extra="仅平台 Skill 可开启"><Switch checked={false} disabled /></Form.Item>}

            </>
          )}
        </Form>
      </Modal>

      <Modal
      title={referenceEditingPath == null ? '新建参考文件' : '重命名参考文件'}
      open={referenceOpen}
      onCancel={() => { referenceDialogSequence.current += 1; setReferenceOpen(false); setReferenceEditingPath(undefined); }}
      onOk={() => void saveReference()}
        okButtonProps={{ disabled: !referenceEditable }}
        destroyOnClose
      >
        <Alert type="info" showIcon message="逻辑路径由服务端校验并写入对象存储；浏览器不会接触服务器文件路径。" />
        <Form form={referenceForm} layout="vertical" style={{ marginTop: 16 }} disabled={!referenceEditable}>
          <Form.Item
            name="resourceKey"
            label="资源键"
            rules={[
              { required: true },
              { pattern: /^[a-z0-9][a-z0-9._-]{0,99}$/, message: '仅小写字母、数字、点、下划线和短横线' }
            ]}
          >
            <Input placeholder="example-guide" />
          </Form.Item>
          <Form.Item
            name="filePath"
            label="逻辑文件路径"
            extra="只允许 references/ 下不超过 192 字符的安全小写相对路径；扩展名由 MIME 类型决定。"
            rules={[
              { required: true },
              {
                validator: (_, value) => isSafeReferencePath(value)
                  ? Promise.resolve()
                  : Promise.reject(new Error('请输入 references/ 下不超过 192 字符的安全小写相对路径'))
              }
            ]}
          >
            <Input maxLength={MAX_REFERENCE_PATH_LENGTH} placeholder="references/example-guide.md" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="将用户公开 Skill 转为私有"
        open={moderationOpen}
        onCancel={() => setModerationOpen(false)}
        onOk={() => void moderateSkill()}
        okButtonProps={{ danger: true, disabled: !assetContextReady }}
        okText="确认转为私有"
        destroyOnClose
      >
        <Alert type="warning" showIcon message="用户内容会保留，操作原因和管理员信息会进入审计。" />
        <Form form={moderationForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="reason"
            label="处理原因"
            rules={[
              { required: true, whitespace: true },
              { min: 4, max: 500 }
            ]}
          >
            <Input.TextArea rows={4} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="发布不可变版本"
        open={publishOpen}
        onCancel={() => setPublishOpen(false)}
        onOk={() => void publish()}
        confirmLoading={publishing}
        okButtonProps={{ disabled: !assetContextReady }}
        destroyOnClose
      >
        <Alert type="warning" showIcon message="发布不会自动切换当前版本或启用身份。" />
        <Form form={publishForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="versionCode"
            label="版本号"
            extra={documentTarget?.assetKind === 'agent' && agentDraft?.packageVersionCode
              ? '内置包草稿保持固定版本；需要新版本时，请先在编辑区执行“转为后续版本”。'
              : undefined}
            rules={[{ required: true }, { pattern: SEMVER, message: '请输入 SemVer，例如 1.0.0' }]}
          >
            <Input disabled={documentTarget?.assetKind === 'agent' && Boolean(agentDraft?.packageVersionCode)} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="Agent 高级配置"
        width="min(960px, 100vw)"
        open={advancedOpen}
        onCancel={closeAdvanced}
        onOk={() => void applyAdvanced()}
        confirmLoading={advancedLoading}
        okButtonProps={{
          disabled: saving || advancedLoading || !assetContextReady
            || !advancedCatalogReady || advancedHasMissingBindings
        }}
        destroyOnClose
      >
        {advancedLoading && <Alert type="info" showIcon message="正在读取当前 Agent 的权威配置目录" />}
        {advancedError && (
          <Alert
            type="error"
            showIcon
            message="目录未就绪，不能应用高级配置"
            description={advancedError}
            action={<Button size="small" onClick={retryAdvancedCatalog}>重试目录</Button>}
          />
        )}
        {advancedCatalogReady && advancedHasMissingBindings && (
          <Alert
            type="warning"
            showIcon
            message="存在当前目录无法解析的历史绑定"
            description={(
              <div>
                <div>草稿尚未改变。请在对应选择框中显式移除以下不可用项后再应用：</div>
                {missingAdvancedSkillLabels.length > 0 && (
                  <div>
                    <Text strong>Skill：</Text>
                    {missingAdvancedSkillLabels.map((label) => (
                      <Tag key={label} color="warning" style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
                        {label}
                      </Tag>
                    ))}
                  </div>
                )}
                {missingAdvancedToolLabels.length > 0 && (
                  <div>
                    <Text strong>Tool：</Text>
                    {missingAdvancedToolLabels.map((label) => (
                      <Tag key={label} color="warning" style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
                        {label}
                      </Tag>
                    ))}
                  </div>
                )}
              </div>
            )}
          />
        )}
        <Form form={agentAdvancedForm} layout="vertical" className="skill-authoring__advanced-form"
          disabled={advancedLoading || saving || !assetContextReady || !advancedCatalogReady}>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="selectableModelCodes"
                label="允许模型"
                rules={[{ required: true, type: 'array', min: 1 }]}
              >
                <Select
                  mode="multiple"
                  showSearch
                  optionFilterProp="searchText"
                  options={agentModels.map((model) => ({
                    value: model.modelCode,
                    disabled: !model.available,
                    searchText: `${model.modelName || ''} ${model.modelCode} ${model.providerName || ''}`,
                    label: modelOptionLabel(model)
                  }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="defaultModelCode" label="默认模型" rules={[{ required: true }]}>
                <Select
                  showSearch
                  optionFilterProp="searchText"
                  options={agentModels.map((model) => ({
                    value: model.modelCode,
                    disabled: !model.available,
                    searchText: `${model.modelName || ''} ${model.modelCode} ${model.providerName || ''}`,
                    label: modelOptionLabel(model)
                  }))}
                />
              </Form.Item>
            </Col>
          </Row>
          <RuntimeAgentResponsePolicyFields />
          <Form.Item
            name="selectedSkillVersionIds"
            label="固定 Skill 版本"
            validateStatus={missingAdvancedSkillIds.length > 0 ? 'error' : undefined}
            help={missingAdvancedSkillIds.length > 0 ? '请显式移除标记为“不可用历史绑定”的 Skill 版本' : undefined}
          >
            <Select
              mode="multiple"
              showSearch
              optionFilterProp="label"
              options={agentSkillSelectOptions}
            />
          </Form.Item>
          <Form.Item
            name="selectedToolCodes"
            label="受控 Tool"
            validateStatus={missingAdvancedToolCodes.length > 0 ? 'error' : undefined}
            help={missingAdvancedToolCodes.length > 0 ? '请显式移除标记为“不可用历史绑定”的 Tool' : undefined}
          >
            <Select
              mode="multiple"
              showSearch
              optionFilterProp="label"
              options={agentToolSelectOptions}
            />
          </Form.Item>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="maxTurns" label="最多回合" rules={[{ required: true }]}>
                <InputNumber min={1} max={64} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="maxToolCalls" label="Tool 调用" rules={[{ required: true }]}>
                <InputNumber min={1} max={64} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="temperatureBasisPoints" label="温度基点" rules={[{ required: true }]}>
                <InputNumber min={0} max={200} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="checkpointPolicy" label="检查点" rules={[{ required: true }]}>
                <Select
                  options={[
                    { value: 'TURN_BOUNDARY', label: '回合边界' },
                    { value: 'TOOL_BOUNDARY', label: 'Tool 边界' }
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
          <Collapse
            items={[
              {
                key: 'input',
                label: '输入 Schema',
                children: (
                  <Form.Item name="inputSchemaJson" noStyle>
                    <SchemaFieldEditor />
                  </Form.Item>
                )
              },
              {
                key: 'output',
                label: '输出 Schema',
                children: (
                  <Form.Item name="outputSchemaJson" noStyle>
                    <SchemaFieldEditor />
                  </Form.Item>
                )
              }
            ]}
          />
          <Paragraph type="secondary" style={{ marginTop: 12 }}>
            未知配置由服务端草稿合并策略保留；这里只提交已展示字段与固定绑定摘要。
          </Paragraph>
        </Form>
      </Modal>

      <SkillPackageManager
        open={!!packageSkill}
        skill={packageSkill}
        canEdit={canSkillEdit && assetContextReady
          && !!selectedSkill && String(selectedSkill.id) === String(packageSkill?.id)}
        onClose={() => setPackageSkill(undefined)}
        onChanged={async () => {
          const target = documentTarget;
          const catalog = await loadCatalog(true);
          const stillVisible = target?.assetKind === 'skill'
            && catalog?.skills?.some((item) => String(item.id) === String(target.assetId));
          if (target && stillVisible) await loadDocument(target);
        }}
      />
    </div>
  );
}
