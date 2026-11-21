import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Select,
  Space, Spin, Table, Tabs, Tag, Typography, message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, EditOutlined, EyeOutlined, PlusOutlined, ReloadOutlined, RocketOutlined, SaveOutlined } from '@ant-design/icons';

import {
  BUILTIN_RUNTIME_AGENTS, RUNTIME_AGENT_PERMISSIONS, activateRuntimeAgentModuleBinding, activateRuntimeAgentVersion,
  createRuntimeAgent, getRuntimeAgentBuiltinSkillDependencies, importBuiltinRuntimeAgent,
  getRuntimeAgentDraft, getRuntimeAgentVersion, listRuntimeAgentModels, listRuntimeAgentSkills,
  listRuntimeAgentModuleBindings, listRuntimeAgentTools, listRuntimeAgentVersions, listRuntimeAgents,
  prepareRuntimeAgentBuiltinSkillDependencies, publishRuntimeAgentDraft,
  saveRuntimeAgentDraft, setRuntimeAgentStatus, validateRuntimeAgentDraft,
  type RuntimeAgentBuiltinImport, type RuntimeAgentBuiltinSkillStatus, type RuntimeAgentDraft, type RuntimeAgentDraftPayload, type RuntimeAgentSkillOption,
  type RuntimeAgentModuleBinding, type RuntimeAgentSummary, type RuntimeAgentToolOption, type RuntimeAgentValidation,
  type RuntimeAgentVersion, type RuntimeAgentBuiltinCode, type RuntimeId
} from '@/api/aid/assistantRuntimeAgent';
import type { SkillTextModelOption } from '@/api/aid/skill';
import { useAuth } from '@/hooks/useAuth';
import { wasRequestErrorReported } from '@/utils/request';
import { resolveRuntimeAgentBindings } from '@/utils/runtimeAgentBindingResolver';
import ContractEditor from '@/views/aid/skill/ContractEditor';
import { isSafeReferencePath, MAX_REFERENCE_PATH_LENGTH } from '@/views/aid/skill/skillPackageUtils';
import RuntimeAgentResponsePolicyFields from './RuntimeAgentResponsePolicyFields';
import { normalizeRuntimeAgentResponsePolicy } from './runtimeAgentResponsePolicy';

const { Text, Paragraph, Title } = Typography;
const SEMVER = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:[-+][A-Za-z0-9.-]+)?$/;
type AgentListLoadResult = 'applied' | 'superseded' | 'failed';

interface EditorValues extends Omit<RuntimeAgentDraftPayload, 'skillBindings' | 'toolBindings'> {
  selectedSkillVersionIds: string[];
  selectedToolCodes: string[];
}

function availability(value: RuntimeAgentSummary | RuntimeAgentVersion) {
  return <Space direction="vertical" size={0}>
    <Tag color={value.runtimeAvailability === 'AVAILABLE' ? 'success' : 'default'}>{value.runtimeAvailability}</Tag>
    {value.runtimeAvailability !== 'AVAILABLE' && <Text type="secondary">{value.runtimeUnavailableReason || '运行时未接通'}</Text>}
  </Space>;
}

function draftToValues(draft: RuntimeAgentDraft): EditorValues {
  return {
    ...draft,
    instruction: draft.systemPrompt,
    resources: draft.resources || [],
    executorType: 'ASSISTANT_LOOP',
    protocolVersion: draft.protocolVersion ?? 'assistant-loop/native-v1',
    modelCapabilityCode: 'text.chat',
    responsePolicy: normalizeRuntimeAgentResponsePolicy(draft.responsePolicy, draft.protocolVersion),
    selectedSkillVersionIds: (draft.skillBindings || []).map((item) => String(item.skillVersionId)),
    selectedToolCodes: (draft.toolBindings || []).map((item) => item.toolCode)
  };
}

interface EditorCatalogSnapshot {
  agentId: RuntimeId;
  sequence: number;
  models: SkillTextModelOption[];
  skills: RuntimeAgentSkillOption[];
  tools: RuntimeAgentToolOption[];
}

type AuxiliaryOperationKind = 'read' | 'prepare' | 'bind';

interface AuxiliaryOperationGate {
  sequence: number;
  operation?: { kind: AuxiliaryOperationKind; token: number };
  reloadAfterOperation: boolean;
}

type AuxiliaryOperationGateRef = { current: AuxiliaryOperationGate };

function beginAuxiliaryOperation(
  gateRef: AuxiliaryOperationGateRef,
  kind: AuxiliaryOperationKind,
  refreshWhenBusy = false
) {
  const gate = gateRef.current;
  if (gate.operation) {
    if (refreshWhenBusy) {
      gate.sequence += 1;
      gate.reloadAfterOperation = true;
    }
    return undefined;
  }
  const token = ++gate.sequence;
  gate.operation = { kind, token };
  return token;
}

function auxiliaryOperationIsCurrent(
  gateRef: AuxiliaryOperationGateRef,
  kind: AuxiliaryOperationKind,
  token: number
) {
  const gate = gateRef.current;
  return gate.sequence === token
    && gate.operation?.kind === kind
    && gate.operation.token === token;
}

function invalidateAuxiliaryOperation(gateRef: AuxiliaryOperationGateRef, reloadAfterOperation: boolean) {
  const gate = gateRef.current;
  gate.sequence += 1;
  gate.reloadAfterOperation = Boolean(gate.operation && reloadAfterOperation);
}

function finishAuxiliaryOperation(
  gateRef: AuxiliaryOperationGateRef,
  kind: AuxiliaryOperationKind,
  token: number
) {
  const gate = gateRef.current;
  if (gate.operation?.kind !== kind || gate.operation.token !== token) return false;
  gate.operation = undefined;
  const shouldReload = gate.reloadAfterOperation;
  gate.reloadAfterOperation = false;
  return shouldReload;
}

export default function AgentVersionPanel({ active }: { active: boolean }) {
  const { hasPermi } = useAuth();
  const canList = hasPermi(RUNTIME_AGENT_PERMISSIONS.list);
  const canQuery = hasPermi(RUNTIME_AGENT_PERMISSIONS.query);
  const canEdit = hasPermi(RUNTIME_AGENT_PERMISSIONS.edit);
  const canPublish = hasPermi(RUNTIME_AGENT_PERMISSIONS.publish);
  const canActivate = hasPermi(RUNTIME_AGENT_PERMISSIONS.activate);
  const [queryForm] = Form.useForm();
  const [createForm] = Form.useForm();
  const [editorForm] = Form.useForm<EditorValues>();
  const selectableModelCodes = Form.useWatch('selectableModelCodes', editorForm) as string[] | undefined;
  const selectedSkillVersionIds = Form.useWatch('selectedSkillVersionIds', editorForm) as string[] | undefined;
  const selectedToolCodes = Form.useWatch('selectedToolCodes', editorForm) as string[] | undefined;
  const [publishForm] = Form.useForm<{ versionCode: string }>();
  const [items, setItems] = useState<RuntimeAgentSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [query, setQuery] = useState<{ keyword?: string; status?: '0' | '1' }>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState<RuntimeAgentSummary>();
  const [draft, setDraft] = useState<RuntimeAgentDraft>();
  const [draftLoading, setDraftLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<RuntimeAgentValidation>();
  const [validationError, setValidationError] = useState('');
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [editorCatalog, setEditorCatalog] = useState<EditorCatalogSnapshot>();
  const [versions, setVersions] = useState<RuntimeAgentVersion[]>([]);
  const [versionTotal, setVersionTotal] = useState(0);
  const [versionPage, setVersionPage] = useState(1);
  const [versionSize, setVersionSize] = useState(20);
  const [versionDetail, setVersionDetail] = useState<RuntimeAgentVersion>();
  const [versionDetailLoadingId, setVersionDetailLoadingId] = useState<RuntimeId>();
  const [moduleBindings, setModuleBindings] = useState<RuntimeAgentModuleBinding[]>([]);
  const [moduleBindingsLoading, setModuleBindingsLoading] = useState(false);
  const [builtinSkills, setBuiltinSkills] = useState<RuntimeAgentBuiltinSkillStatus>();
  const [builtinSkillsLoading, setBuiltinSkillsLoading] = useState(false);
  const [preparingBuiltinSkills, setPreparingBuiltinSkills] = useState(false);
  const [importingCode, setImportingCode] = useState('');
  const [bindingModule, setBindingModule] = useState('');
  const [drawerTab, setDrawerTab] = useState('draft');
  const [statusPendingIds, setStatusPendingIds] = useState<Set<string>>(() => new Set());
  const sequence = useRef(0);
  const statusOperations = useRef<Set<string>>(new Set());
  const listLoaderRef = useRef<(force?: boolean) => Promise<AgentListLoadResult>>();
  const statusAccessToken = JSON.stringify([active, canList, canEdit]);
  const currentStatusContext = useRef({ active, canList, canEdit, token: statusAccessToken, revision: 0 });
  currentStatusContext.current = currentStatusContext.current.token === statusAccessToken
    ? { ...currentStatusContext.current, active, canList, canEdit }
    : { active, canList, canEdit, token: statusAccessToken, revision: currentStatusContext.current.revision + 1 };
  const openAgentSequence = useRef(0);
  const versionListSequence = useRef(0);
  const versionDetailSequence = useRef(0);
  const selectedAgentIdRef = useRef<RuntimeId>();
  const selectedRef = useRef<RuntimeAgentSummary>();
  const dirtyRef = useRef(false);
  const editorRevision = useRef(0);
  const editorOperationSequence = useRef(0);
  const editorOperationRef = useRef<{ kind: 'save' | 'validate' | 'publish'; token: number }>();
  const mountedRef = useRef(false);
  const activeRef = useRef(active);
  const canQueryRef = useRef(canQuery);
  const canEditRef = useRef(canEdit);
  const canActivateRef = useRef(canActivate);
  const moduleBindingGate = useRef<AuxiliaryOperationGate>({ sequence: 0, reloadAfterOperation: false });
  const builtinSkillGate = useRef<AuxiliaryOperationGate>({ sequence: 0, reloadAfterOperation: false });
  const builtinImportOperation = useRef('');
  const builtinImportConfirmRef = useRef<ReturnType<typeof Modal.confirm>>();
  const moduleBindingLoaderRef = useRef<(force?: boolean) => Promise<void>>();
  const builtinSkillLoaderRef = useRef<(force?: boolean) => Promise<void>>();
  activeRef.current = active;
  canQueryRef.current = canQuery;
  canEditRef.current = canEdit;
  canActivateRef.current = canActivate;
  selectedRef.current = selected;
  dirtyRef.current = dirty;
  const editorCatalogReady = Boolean(
    active
    && canQuery
    && selected
    && draft
    && editorCatalog
    && editorCatalog.sequence === openAgentSequence.current
    && String(editorCatalog.agentId) === String(selectedAgentIdRef.current)
    && String(editorCatalog.agentId) === String(selected.id)
    && String(editorCatalog.agentId) === String(draft.agentId)
  );
  const models = editorCatalogReady ? editorCatalog!.models : [];
  const skills = editorCatalogReady ? editorCatalog!.skills : [];
  const tools = editorCatalogReady ? editorCatalog!.tools : [];
  const editorBindingResolution = editorCatalogReady
    ? resolveRuntimeAgentBindings(
      selectedSkillVersionIds || [],
      selectedToolCodes || [],
      skills,
      tools
    )
    : undefined;
  const editorBindingsReady = Boolean(editorBindingResolution?.complete);

  const listViewToken = JSON.stringify([active, canList, pageNum, pageSize, query.keyword || '', query.status || '']);
  const currentListView = useRef(listViewToken);
  currentListView.current = listViewToken;

  const load = useCallback(async (force = false): Promise<AgentListLoadResult> => {
    const current = ++sequence.current;
    const requestedView = listViewToken;
    if (!mountedRef.current || !active || !canList) return 'superseded';
    setLoading(true); setError('');
    try {
      const response = await listRuntimeAgents({ pageNum, pageSize, ...query }, force);
      if (!mountedRef.current || current !== sequence.current || requestedView !== currentListView.current) return 'superseded';
      setItems(response.data || []); setTotal(Number(response.total || 0));
      return 'applied';
    } catch (cause: any) {
      if (!mountedRef.current || current !== sequence.current || requestedView !== currentListView.current) return 'superseded';
      setError(cause?.message || 'Runtime Agent列表读取失败');
      return 'failed';
    } finally {
      if (mountedRef.current && current === sequence.current && requestedView === currentListView.current) setLoading(false);
    }
  }, [active, canList, listViewToken, pageNum, pageSize, query]);
  listLoaderRef.current = load;

  useEffect(() => {
    if (active && canList) return;
    sequence.current += 1;
    setLoading(false);
  }, [active, canList]);

  const loadModuleBindings = useCallback(async (force = false) => {
    if (!activeRef.current || !canQueryRef.current) return;
    const token = beginAuxiliaryOperation(moduleBindingGate, 'read', force);
    if (token == null) return;
    setModuleBindingsLoading(true);
    try {
      const response = await listRuntimeAgentModuleBindings(force);
      if (!auxiliaryOperationIsCurrent(moduleBindingGate, 'read', token)
        || !mountedRef.current || !activeRef.current || !canQueryRef.current) return;
      setModuleBindings(response.data || []);
    } catch (cause: any) {
      if (auxiliaryOperationIsCurrent(moduleBindingGate, 'read', token)
        && mountedRef.current && activeRef.current && canQueryRef.current) {
        message.error(cause?.message || '模块绑定读取失败');
      }
    } finally {
      const shouldReload = finishAuxiliaryOperation(moduleBindingGate, 'read', token);
      if (mountedRef.current) setModuleBindingsLoading(false);
      if (shouldReload && mountedRef.current && activeRef.current && canQueryRef.current) {
        void Promise.resolve().then(() => moduleBindingLoaderRef.current?.(true));
      }
    }
  }, []);

  const loadBuiltinSkills = useCallback(async (force = false) => {
    if (!activeRef.current || !canQueryRef.current) return;
    const token = beginAuxiliaryOperation(builtinSkillGate, 'read', force);
    if (token == null) return;
    setBuiltinSkillsLoading(true);
    try {
      const response = await getRuntimeAgentBuiltinSkillDependencies(force);
      if (!auxiliaryOperationIsCurrent(builtinSkillGate, 'read', token)
        || !mountedRef.current || !activeRef.current || !canQueryRef.current) return;
      setBuiltinSkills(response.data);
    } catch (cause: any) {
      if (auxiliaryOperationIsCurrent(builtinSkillGate, 'read', token)
        && mountedRef.current && activeRef.current && canQueryRef.current) {
        message.error(cause?.message || '内置 Skill 依赖读取失败');
      }
    } finally {
      const shouldReload = finishAuxiliaryOperation(builtinSkillGate, 'read', token);
      if (mountedRef.current) setBuiltinSkillsLoading(false);
      if (shouldReload && mountedRef.current && activeRef.current && canQueryRef.current) {
        void Promise.resolve().then(() => builtinSkillLoaderRef.current?.(true));
      }
    }
  }, []);

  moduleBindingLoaderRef.current = loadModuleBindings;
  builtinSkillLoaderRef.current = loadBuiltinSkills;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      sequence.current += 1;
      statusOperations.current.clear();
      builtinImportOperation.current = '';
      builtinImportConfirmRef.current?.destroy();
      invalidateAuxiliaryOperation(moduleBindingGate, false);
      invalidateAuxiliaryOperation(builtinSkillGate, false);
    };
  }, []);

  useEffect(() => {
    invalidateAuxiliaryOperation(moduleBindingGate, active && canQuery);
    if (!active || !canQuery) {
      setModuleBindingsLoading(false);
      if (!canQuery) setModuleBindings([]);
      return;
    }
    setModuleBindingsLoading(moduleBindingGate.current.operation?.kind === 'read');
    void loadModuleBindings();
  }, [active, canActivate, canQuery, loadModuleBindings]);

  useEffect(() => {
    invalidateAuxiliaryOperation(builtinSkillGate, active && canQuery);
    if (!active || !canQuery) {
      setBuiltinSkillsLoading(false);
      if (!canQuery) setBuiltinSkills(undefined);
      return;
    }
    setBuiltinSkillsLoading(builtinSkillGate.current.operation?.kind === 'read');
    setPreparingBuiltinSkills(builtinSkillGate.current.operation?.kind === 'prepare');
    void loadBuiltinSkills();
  }, [active, canEdit, canQuery, loadBuiltinSkills]);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);

  const buildPayload = async (agentId: RuntimeId): Promise<RuntimeAgentDraftPayload> => {
    const catalog = editorCatalog;
    if (!editorCatalogReady || !catalog || String(catalog.agentId) !== String(agentId)) {
      throw new Error('当前 Agent 的模型、Skill 与 Tool 目录尚未就绪');
    }
    const values = await editorForm.validateFields();
    if (catalog.sequence !== openAgentSequence.current
      || String(catalog.agentId) !== String(selectedAgentIdRef.current)
      || String(catalog.agentId) !== String(agentId)) {
      throw new Error('当前 Agent 的配置目录已失效，请重新打开');
    }
    if (!values.selectableModelCodes?.includes(values.defaultModelCode)) {
      editorForm.setFields([{ name: 'defaultModelCode', errors: ['默认模型必须属于允许模型'] }]);
      throw new Error('默认模型必须属于允许模型');
    }
    const { selectedSkillVersionIds: selectedSkillIds, selectedToolCodes: selectedTools, ...draftValues } = values;
    const bindingResolution = resolveRuntimeAgentBindings(
      selectedSkillIds || [],
      selectedTools || [],
      catalog.skills,
      catalog.tools
    );
    if (!bindingResolution.complete) {
      throw new Error('请先显式移除全部不可用历史绑定，并确认目录中没有重复 ID 或编码');
    }
    return {
      ...draftValues,
      mainMarkdown: values.systemPrompt,
      instruction: values.systemPrompt,
      packageVersionCode: draft?.packageVersionCode,
      agentId,
      baseVersionId: draft?.baseVersionId,
      executorType: 'ASSISTANT_LOOP',
      protocolVersion: values.protocolVersion ?? 'assistant-loop/native-v1',
      modelCapabilityCode: 'text.chat',
      responsePolicy: normalizeRuntimeAgentResponsePolicy({
        ...draft?.responsePolicy,
        ...values.responsePolicy
      }, values.protocolVersion),
      skillBindings: bindingResolution.skillBindings,
      toolBindings: bindingResolution.toolBindings
    };
  };

  const isCurrentAgent = (agentId: RuntimeId) =>
    mountedRef.current && String(selectedAgentIdRef.current) === String(agentId);

  const loadVersions = async (agentId: RuntimeId, page = versionPage, size = versionSize, force = false) => {
    const request = ++versionListSequence.current;
    const response = await listRuntimeAgentVersions(agentId, page, size, force);
    if (request !== versionListSequence.current || !isCurrentAgent(agentId)) return;
    setVersions(response.data || []);
    setVersionTotal(Number(response.total || 0));
  };

  const reloadSelectedAgent = async (agentId: RuntimeId) => {
    const request = ++sequence.current;
    const response = await listRuntimeAgents({ pageNum, pageSize, ...query }, true);
    if (request !== sequence.current || !isCurrentAgent(agentId)) return;
    setItems(response.data || []);
    setTotal(Number(response.total || 0));
    const agent = (response.data || []).find((item) => String(item.id) === String(agentId));
    if (agent) setSelected(agent);
  };

  const closeVersionDetail = () => {
    versionDetailSequence.current += 1;
    setVersionDetailLoadingId(undefined);
    setVersionDetail(undefined);
  };

  const closeAgentNow = () => {
    openAgentSequence.current += 1;
    versionListSequence.current += 1;
    closeVersionDetail();
    selectedAgentIdRef.current = undefined;
    editorOperationRef.current = undefined;
    setSelected(undefined);
    setDraft(undefined);
    setEditorCatalog(undefined);
    setVersions([]);
    setVersionTotal(0);
    setValidation(undefined);
    setValidationError('');
    setDirty(false);
    setDraftLoading(false);
    setPublishOpen(false);
    editorForm.resetFields();
  };

  const requestCloseAgent = () => {
    if (editorOperationRef.current) {
      message.warning('Agent 草稿操作正在处理中，请稍候');
      return;
    }
    if (!dirty) {
      closeAgentNow();
      return;
    }
    Modal.confirm({
      title: '放弃未保存的 Agent 修改？',
      content: '关闭后会保留服务端上次保存的草稿。',
      okText: '放弃并关闭',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: closeAgentNow
    });
  };

  const openAgentNow = async (agent: RuntimeAgentSummary) => {
    if (!mountedRef.current || !activeRef.current || !canQueryRef.current) return;
    const request = ++openAgentSequence.current;
    versionListSequence.current += 1;
    closeVersionDetail();
    selectedAgentIdRef.current = agent.id;
    setSelected(agent);
    setDraft(undefined);
    setEditorCatalog(undefined);
    setVersions([]);
    setVersionTotal(0);
    setDraftLoading(true);
    setValidation(undefined);
    setValidationError('');
    setDirty(false);
    setDrawerTab('draft');
    editorForm.resetFields();
    try {
      const [draftResponse, modelResponse, skillResponse, toolResponse] = await Promise.all([
        getRuntimeAgentDraft(agent.id, undefined, true), listRuntimeAgentModels(),
        listRuntimeAgentSkills(), listRuntimeAgentTools()
      ]);
      if (request !== openAgentSequence.current || !isCurrentAgent(agent.id)
        || !activeRef.current || !canQueryRef.current) return;
      if (typeof draftResponse.data.mainMarkdown !== 'string'
        || typeof draftResponse.data.systemPrompt !== 'string'
        || !Array.isArray(draftResponse.data.resources)
        || draftResponse.data.resources.some((resource) => !resource.filePath)) {
        throw new Error('服务端尚未返回 Agent 主文件、系统提示词或受控参考文件路径，请先升级 Runtime 接口');
      }
      if (!Array.isArray(modelResponse.data)
        || !Array.isArray(skillResponse.data)
        || !Array.isArray(toolResponse.data)) {
        throw new Error('模型、Skill 或 Tool 目录响应格式无效');
      }
      const modelCodes = new Set<string>();
      modelResponse.data.forEach((item) => {
        if (modelCodes.has(item.modelCode)) throw new Error(`模型目录包含重复编码：${item.modelCode}`);
        modelCodes.add(item.modelCode);
      });
      const emptyBindingResolution = resolveRuntimeAgentBindings([], [], skillResponse.data, toolResponse.data);
      if (emptyBindingResolution.duplicateCatalogSkillVersionIds.length > 0
        || emptyBindingResolution.duplicateCatalogToolCodes.length > 0
        || emptyBindingResolution.invalidCatalogSkillEntries > 0
        || emptyBindingResolution.invalidCatalogToolEntries > 0) {
        throw new Error('Skill 或 Tool 目录包含重复或空的 ID / 编码');
      }
      setDraft(draftResponse.data);
      setEditorCatalog({
        agentId: agent.id,
        sequence: request,
        models: modelResponse.data,
        skills: skillResponse.data,
        tools: toolResponse.data
      });
      editorForm.setFieldsValue(draftToValues(draftResponse.data));
      editorRevision.current += 1;
      setDirty(false);
      setVersionPage(1);
      setVersionSize(20);
      await loadVersions(agent.id, 1, 20, true);
    } catch (cause: any) {
      if (request === openAgentSequence.current && isCurrentAgent(agent.id)) {
        message.error(cause?.message || 'Agent配置读取失败');
      }
    } finally {
      if (request === openAgentSequence.current && isCurrentAgent(agent.id)) setDraftLoading(false);
    }
  };

  const requestOpenAgent = (agent: RuntimeAgentSummary) => {
    if (editorOperationRef.current) {
      message.warning('Agent 草稿操作正在处理中，请稍候');
      return;
    }
    if (String(selectedAgentIdRef.current) === String(agent.id)) return;
    if (!dirty || !selectedAgentIdRef.current) {
      void openAgentNow(agent);
      return;
    }
    Modal.confirm({
      title: '放弃未保存的 Agent 修改？',
      content: '切换 Agent 会恢复服务端上次保存的草稿。',
      okText: '放弃并切换',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: () => void openAgentNow(agent)
    });
  };

  const beginEditorOperation = (kind: 'save' | 'validate' | 'publish') => {
    if (editorOperationRef.current) return undefined;
    const token = ++editorOperationSequence.current;
    editorOperationRef.current = { kind, token };
    return token;
  };

  const operationIsCurrent = (kind: 'save' | 'validate' | 'publish', token: number, agentId: RuntimeId) =>
    editorOperationRef.current?.kind === kind && editorOperationRef.current.token === token
    && isCurrentAgent(agentId);

  const finishEditorOperation = (kind: 'save' | 'validate' | 'publish', token: number) => {
    if (editorOperationRef.current?.kind === kind && editorOperationRef.current.token === token) {
      editorOperationRef.current = undefined;
    }
  };

  const save = async () => {
    if (!selected || !draft) return;
    if (!editorCatalogReady || !editorBindingsReady) {
      message.warning('请先显式移除全部不可用历史绑定，并确认当前目录已就绪');
      return;
    }
    const agentId = selected.id;
    const token = beginEditorOperation('save');
    if (token == null) return;
    const revision = editorRevision.current;
    setSaving(true);
    try {
      const payload = await buildPayload(agentId);
      if (!operationIsCurrent('save', token, agentId) || revision !== editorRevision.current) return;
      const response = await saveRuntimeAgentDraft({
        ...payload,
        draftId: draft.draftId,
        draftDigest: draft.draftDigest
      });
      if (!operationIsCurrent('save', token, agentId) || revision !== editorRevision.current) return;
      setDraft(response.data);
      editorForm.setFieldsValue(draftToValues(response.data));
      editorRevision.current += 1;
      setDirty(false);
      setValidation(undefined);
      setValidationError('');
      message.success('Agent草稿已保存');
    } catch (cause: any) {
      if (operationIsCurrent('save', token, agentId)) message.error(cause?.message || 'Agent草稿保存失败');
    } finally {
      finishEditorOperation('save', token);
      setSaving(false);
    }
  };

  const convertToFollowupVersion = async () => {
    if (!selected || !draft?.packageVersionCode) return;
    if (!editorCatalogReady || !editorBindingsReady) {
      message.warning('请先显式移除全部不可用历史绑定，并确认当前目录已就绪');
      return;
    }
    const agentId = selected.id;
    const currentDraft = draft;
    const token = beginEditorOperation('save');
    if (token == null) return;
    const revision = editorRevision.current;
    setSaving(true);
    try {
      const payload = await buildPayload(agentId);
      if (!operationIsCurrent('save', token, agentId) || revision !== editorRevision.current) return;
      const response = await saveRuntimeAgentDraft({
        ...payload,
        packageVersionCode: null,
        draftId: currentDraft.draftId,
        draftDigest: currentDraft.draftDigest
      });
      if (!operationIsCurrent('save', token, agentId) || revision !== editorRevision.current) return;
      if (response.data.packageVersionCode || !response.data.draftDigest
        || response.data.draftDigest === currentDraft.draftDigest) {
        throw new Error('服务端未返回已解除版本锁的新草稿摘要，请刷新后重试');
      }
      setDraft(response.data);
      editorForm.setFieldsValue(draftToValues(response.data));
      editorRevision.current += 1;
      setDirty(false);
      setValidation(undefined);
      setValidationError('');
      setPublishOpen(false);
      publishForm.resetFields();
      message.success('已转为后续版本草稿；请填写下一版本号并重新校验');
    } catch (cause: any) {
      if (operationIsCurrent('save', token, agentId)) {
        const text = cause?.message || '转换后续版本草稿失败';
        if (!wasRequestErrorReported(cause)) message.error(text);
      }
    } finally {
      finishEditorOperation('save', token);
      setSaving(false);
    }
  };

  const requestConvertToFollowupVersion = () => {
    if (!draft?.packageVersionCode) return;
    Modal.confirm({
      title: '转为后续版本草稿？',
      content: `当前草稿固定为内置包版本 ${draft.packageVersionCode}。转换会通过摘要并发校验保存当前全部配置和参考文件，并解除固定版本；历史正式版本不会改变。转换后请填写下一版本号并重新校验。`,
      okText: '保存并转换',
      cancelText: '取消',
      onOk: () => convertToFollowupVersion()
    });
  };

  const validate = async () => {
    if (!selected || !draft) return;
    if (!editorCatalogReady || !editorBindingsReady) {
      message.warning('请先显式移除全部不可用历史绑定，并确认当前目录已就绪');
      return;
    }
    const agentId = selected.id;
    const token = beginEditorOperation('validate');
    if (token == null) return;
    const revision = editorRevision.current;
    setValidating(true);
    setValidation(undefined);
    setValidationError('');
    try {
      const payload = await buildPayload(agentId);
      if (!operationIsCurrent('validate', token, agentId)) return;
      if (revision !== editorRevision.current) {
        setValidationError('校验准备期间配置发生变化，本次请求未发送。请确认当前内容后重新校验。');
        return;
      }
      const response = await validateRuntimeAgentDraft(payload);
      if (!operationIsCurrent('validate', token, agentId)) return;
      if (revision !== editorRevision.current) {
        setValidationError('校验期间配置发生变化，本次返回结果已忽略。请对当前内容重新校验。');
        return;
      }
      setValidation(response.data);
      if (response.data.valid) message.success('配置校验通过');
      else message.error('配置仍有校验错误');
    } catch (cause: any) {
      if (operationIsCurrent('validate', token, agentId)) {
        const text = cause?.errorFields ? '请先修正表单中标记的配置项' : cause?.message || '配置校验失败';
        setValidationError(text);
        if (!wasRequestErrorReported(cause)) message.error(text);
      }
    } finally {
      finishEditorOperation('validate', token);
      setValidating(false);
    }
  };

  const publish = async () => {
    if (!draft?.draftId || !draft.draftDigest || !selected || dirty) return;
    const agentId = selected.id;
    const token = beginEditorOperation('publish');
    if (token == null) return;
    const revision = editorRevision.current;
    const draftId = draft.draftId;
    const draftDigest = draft.draftDigest;
    setPublishing(true);
    try {
      const values = await publishForm.validateFields();
      if (!operationIsCurrent('publish', token, agentId) || revision !== editorRevision.current) return;
      await publishRuntimeAgentDraft(draftId, draftDigest, values.versionCode);
      if (!operationIsCurrent('publish', token, agentId) || revision !== editorRevision.current) return;
      const response = await getRuntimeAgentDraft(agentId, undefined, true);
      if (!operationIsCurrent('publish', token, agentId) || revision !== editorRevision.current) return;
      setDraft(response.data);
      editorForm.setFieldsValue(draftToValues(response.data));
      editorRevision.current += 1;
      setDirty(false);
      setPublishOpen(false);
      publishForm.resetFields();
      setValidation(undefined);
      setValidationError('');
      message.success('版本已冻结发布；运行可用性仍由服务端独立判断');
      await Promise.allSettled([loadVersions(agentId, 1, versionSize, true), load(true)]);
    } catch (cause: any) {
      if (operationIsCurrent('publish', token, agentId) && !cause?.errorFields) {
        const text = cause?.message || '版本发布失败';
        publishForm.setFields([{ name: 'versionCode', errors: [text] }]);
        if (!wasRequestErrorReported(cause)) message.error(text);
      }
    } finally {
      finishEditorOperation('publish', token);
      setPublishing(false);
    }
  };

  const activate = async (version: RuntimeAgentVersion) => {
    if (!selected) return;
    try {
      await activateRuntimeAgentVersion(selected.id, version.id, selected.currentVersionId);
      message.success(version.current ? '当前版本未变化' : '当前版本已切换');
    } catch (cause: any) { message.error(cause?.message || '版本切换失败'); }
    finally {
      await Promise.allSettled([
        reloadSelectedAgent(selected.id),
        loadVersions(selected.id, versionPage, versionSize, true),
        loadModuleBindings(true)
      ]);
    }
  };

  const create = async () => {
    setCreating(true);
    try {
      const values = await createForm.validateFields();
      await createRuntimeAgent(values);
      message.success('Runtime Agent已创建，默认停用'); setCreateOpen(false); createForm.resetFields(); await load(true);
    } catch (cause: any) { message.error(cause?.message || 'Runtime Agent创建失败'); }
    finally { setCreating(false); }
  };

  const refreshAfterBuiltinImport = async (agentId: RuntimeId) => {
    await Promise.allSettled([load(true), loadModuleBindings(true)]);
    if (!mountedRef.current || !activeRef.current || !canQueryRef.current
      || String(selectedAgentIdRef.current) !== String(agentId) || dirtyRef.current) return;
    const currentSelected = selectedRef.current;
    if (currentSelected && String(currentSelected.id) === String(agentId)) {
      await openAgentNow(currentSelected);
    }
  };

  const replaceBuiltinDraft = async (receipt: RuntimeAgentBuiltinImport) => {
    if (!mountedRef.current || !activeRef.current || !canEditRef.current) {
      message.warning('当前页面或编辑权限已变化，请重新检查内置草稿');
      return;
    }
    const operationKey = `${receipt.agentCode}:replace`;
    if (builtinImportOperation.current) return;
    builtinImportOperation.current = operationKey;
    setImportingCode(receipt.agentCode);
    try {
      const response = await importBuiltinRuntimeAgent({
        agentCode: receipt.agentCode,
        replaceExistingDraft: true,
        expectedDraftId: receipt.draftId!,
        expectedDraftDigest: receipt.draftDigest!
      });
      if (response.data.state !== 'UPDATED_DRAFT'
        || String(response.data.draftId) !== String(receipt.draftId)) {
        throw new Error('服务端未返回已更新的同一草稿，请刷新后重新检查');
      }
      message.success(response.data.message || '已用当前内置包更新本人草稿');
      await refreshAfterBuiltinImport(receipt.agentId);
    } catch (cause: any) {
      if (Number(cause?.code) === 409) {
        await refreshAfterBuiltinImport(receipt.agentId);
        if (!wasRequestErrorReported(cause)) message.error(cause?.message || '草稿已变化，请检查刷新后的内容');
        else message.warning('草稿已变化，已刷新当前内容；系统没有自动重试更新');
        return;
      }
      if (!wasRequestErrorReported(cause)) message.error(cause?.message || '内置Agent草稿更新失败');
      throw cause;
    } finally {
      if (builtinImportOperation.current === operationKey) builtinImportOperation.current = '';
      if (mountedRef.current) setImportingCode('');
    }
  };

  const confirmBuiltinDraftReplacement = (receipt: RuntimeAgentBuiltinImport) => {
    const validDraftId = receipt.draftId != null && /^[1-9][0-9]*$/.test(String(receipt.draftId));
    const validDigest = typeof receipt.draftDigest === 'string'
      && /^[a-f0-9]{64}$/i.test(receipt.draftDigest);
    const validBuiltinVersion = typeof receipt.builtinPackageVersionCode === 'string'
      && receipt.builtinPackageVersionCode.trim().length > 0;
    if (!validDraftId || !validDigest || !validBuiltinVersion) {
      message.error('服务端未返回可确认的草稿身份、摘要或内置包版本，本次未更新');
      return;
    }
    builtinImportConfirmRef.current?.destroy();
    const confirmation = Modal.confirm({
      title: '以当前内置包更新本人草稿？',
      width: 640,
      okText: '以当前内置包更新本人草稿',
      okButtonProps: { danger: true },
      cancelText: '先保留并审阅',
      content: <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="当前草稿包版本">
            {receipt.draftPackageVersionCode || '未标记'}
          </Descriptions.Item>
          <Descriptions.Item label="新的内置包版本">
            {receipt.builtinPackageVersionCode}
          </Descriptions.Item>
          <Descriptions.Item label="当前草稿 ID">
            <Text code copyable>{String(receipt.draftId)}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="当前草稿摘要">
            <Text code copyable={{ text: receipt.draftDigest! }} style={{ wordBreak: 'break-all' }}>
              {receipt.draftDigest}
            </Text>
          </Descriptions.Item>
        </Descriptions>
        <Alert type="warning" showIcon message="首次检查没有改动草稿"
          description="选择“先保留并审阅”后，可在当前 Agent 草稿编辑器查看并复制旧正文；再次点击导入会重新读取草稿摘要。" />
        <Paragraph style={{ marginBottom: 0 }}>
          确认后会替换本人当前草稿的正文、参考资源、Skill/Tool 绑定和输入输出 Schema；模型能力、默认模型、允许模型和温度配置保持不变。已发布历史版本及其运行记录不会变化。
        </Paragraph>
      </Space>,
      onOk: () => replaceBuiltinDraft(receipt),
      afterClose: () => {
        if (builtinImportConfirmRef.current === confirmation) builtinImportConfirmRef.current = undefined;
      }
    });
    builtinImportConfirmRef.current = confirmation;
  };

  const importBuiltin = async (agentCode: RuntimeAgentBuiltinCode) => {
    if (!mountedRef.current || !activeRef.current || !canEditRef.current || builtinImportOperation.current) return;
    const currentSelected = selectedRef.current;
    if (currentSelected?.agentCode === agentCode && dirtyRef.current) {
      message.warning('请先保存或复制当前未保存内容，再检查内置包更新');
      return;
    }
    const operationKey = `${agentCode}:inspect`;
    builtinImportOperation.current = operationKey;
    setImportingCode(agentCode);
    try {
      const response = await importBuiltinRuntimeAgent({ agentCode });
      if (response.data.state === 'EXISTING_DRAFT') {
        confirmBuiltinDraftReplacement(response.data);
        return;
      }
      message.success(response.data.message || (response.data.state === 'CREATED' ? '已导入草稿' : 'Agent已存在'));
      await refreshAfterBuiltinImport(response.data.agentId);
    } catch (cause: any) {
      if (!wasRequestErrorReported(cause)) message.error(cause?.message || '内置Agent导入失败');
    } finally {
      if (builtinImportOperation.current === operationKey) builtinImportOperation.current = '';
      if (mountedRef.current) setImportingCode('');
    }
  };

  const prepareBuiltinSkills = async () => {
    if (!activeRef.current || !canQueryRef.current || !canEditRef.current) return;
    const token = beginAuxiliaryOperation(builtinSkillGate, 'prepare');
    if (token == null) return;
    let prepareFailed = false;
    setPreparingBuiltinSkills(true);
    try {
      const response = await prepareRuntimeAgentBuiltinSkillDependencies();
      if (!auxiliaryOperationIsCurrent(builtinSkillGate, 'prepare', token)
        || !mountedRef.current || !activeRef.current || !canQueryRef.current || !canEditRef.current) return;
      setBuiltinSkills(response.data);
      message.success('已准备缺失的内置 Skill 草稿');
    } catch (cause: any) {
      prepareFailed = true;
      if (auxiliaryOperationIsCurrent(builtinSkillGate, 'prepare', token)
        && mountedRef.current && activeRef.current && canQueryRef.current && canEditRef.current) {
        message.error(cause?.message || '内置 Skill 草稿准备失败');
      }
    } finally {
      if (prepareFailed && auxiliaryOperationIsCurrent(builtinSkillGate, 'prepare', token)
        && mountedRef.current && activeRef.current && canQueryRef.current) {
        try {
          const response = await getRuntimeAgentBuiltinSkillDependencies(true);
          if (auxiliaryOperationIsCurrent(builtinSkillGate, 'prepare', token)
            && mountedRef.current && activeRef.current && canQueryRef.current) {
            setBuiltinSkills(response.data);
          }
        } catch (cause: any) {
          if (auxiliaryOperationIsCurrent(builtinSkillGate, 'prepare', token)
            && mountedRef.current && activeRef.current && canQueryRef.current) {
            message.error(cause?.message || '内置 Skill 依赖状态刷新失败');
          }
        }
      }
      const shouldReload = finishAuxiliaryOperation(builtinSkillGate, 'prepare', token);
      if (mountedRef.current) setPreparingBuiltinSkills(false);
      if (shouldReload && mountedRef.current && activeRef.current && canQueryRef.current) {
        void Promise.resolve().then(() => builtinSkillLoaderRef.current?.(true));
      }
    }
  };

  const bindModule = async (binding: RuntimeAgentModuleBinding, version: RuntimeAgentVersion) => {
    if (!selected || !activeRef.current || !canQueryRef.current || !canActivateRef.current) return;
    const token = beginAuxiliaryOperation(moduleBindingGate, 'bind');
    if (token == null) return;
    setBindingModule(binding.moduleCode);
    try {
      await activateRuntimeAgentModuleBinding({
        moduleCode: binding.moduleCode,
        agentId: selected.id,
        versionId: version.id,
        expectedBindingRevision: binding.bindingRevision
      });
      if (!auxiliaryOperationIsCurrent(moduleBindingGate, 'bind', token)
        || !mountedRef.current || !activeRef.current || !canQueryRef.current || !canActivateRef.current) return;
      message.success(`${binding.moduleCode} 主Agent已绑定`);
    } catch (cause: any) {
      if (auxiliaryOperationIsCurrent(moduleBindingGate, 'bind', token)
        && mountedRef.current && activeRef.current && canQueryRef.current && canActivateRef.current) {
        message.error(cause?.message || '模块绑定失败');
      }
    } finally {
      if (auxiliaryOperationIsCurrent(moduleBindingGate, 'bind', token)
        && mountedRef.current && activeRef.current && canQueryRef.current) {
        try {
          const response = await listRuntimeAgentModuleBindings(true);
          if (auxiliaryOperationIsCurrent(moduleBindingGate, 'bind', token)
            && mountedRef.current && activeRef.current && canQueryRef.current) {
            setModuleBindings(response.data || []);
          }
        } catch (cause: any) {
          if (auxiliaryOperationIsCurrent(moduleBindingGate, 'bind', token)
            && mountedRef.current && activeRef.current && canQueryRef.current) {
            message.error(cause?.message || '模块绑定状态刷新失败');
          }
        }
      }
      const shouldReload = finishAuxiliaryOperation(moduleBindingGate, 'bind', token);
      if (mountedRef.current) setBindingModule('');
      if (shouldReload && mountedRef.current && activeRef.current && canQueryRef.current) {
        void Promise.resolve().then(() => moduleBindingLoaderRef.current?.(true));
      }
    }
  };

  const changeStatus = async (agent: RuntimeAgentSummary, status: '0' | '1') => {
    const operationKey = String(agent.id);
    if (!mountedRef.current || !currentStatusContext.current.active
      || !currentStatusContext.current.canList || !currentStatusContext.current.canEdit
      || statusOperations.current.has(operationKey)) return;
    statusOperations.current.add(operationKey);
    const operationAccessRevision = currentStatusContext.current.revision;
    setStatusPendingIds((current) => new Set(current).add(operationKey));
    try {
      const response = await setRuntimeAgentStatus(agent.id, status);
      const receipt = response.data;
      if (!receipt || String(receipt.id) !== String(agent.id) || receipt.status !== status) {
        throw new Error('状态修改回执无效，请刷新后确认');
      }
      if (!mountedRef.current || currentStatusContext.current.revision !== operationAccessRevision
        || !currentStatusContext.current.active
        || !currentStatusContext.current.canList || !currentStatusContext.current.canEdit) return;
      const mergeReceipt = (row: RuntimeAgentSummary) => String(row.id) === String(receipt.id)
        ? { ...row, status: receipt.status, updateTime: receipt.updateTime }
        : row;
      setItems((current) => current.map(mergeReceipt));
      setSelected((current) => current ? mergeReceipt(current) : current);
      message.success(status === '0' ? 'Agent已启用' : 'Agent已停用');
      const refreshed = await listLoaderRef.current?.(true);
      if (refreshed === 'failed' && mountedRef.current
        && currentStatusContext.current.revision === operationAccessRevision && currentStatusContext.current.active
        && currentStatusContext.current.canList && currentStatusContext.current.canEdit) {
        message.warning('状态已更新，但列表刷新失败，请稍后重试');
      }
    } catch (cause: any) {
      if (mountedRef.current && currentStatusContext.current.revision === operationAccessRevision
        && currentStatusContext.current.active
        && currentStatusContext.current.canList && currentStatusContext.current.canEdit) {
        message.error(cause?.message || '状态修改失败');
      }
    }
    finally {
      statusOperations.current.delete(operationKey);
      if (mountedRef.current) {
        setStatusPendingIds((current) => {
          const next = new Set(current); next.delete(operationKey); return next;
        });
      }
    }
  };

  const columns: ColumnsType<RuntimeAgentSummary> = [
    { title: 'Agent', width: 240, render: (_, row) => <Space direction="vertical" size={0}><Text strong>{row.name}</Text><Text code>{row.agentCode}</Text></Space> },
    { title: '可见性', dataIndex: 'visibility', width: 100, render: (value) => <Tag>{value}</Tag> },
    { title: '当前版本', dataIndex: 'currentVersionId', width: 150, render: (value) => value == null ? '未激活' : String(value) },
    { title: '运行可用性', width: 250, render: (_, row) => availability(row) },
    { title: '状态', width: 100, render: (_, row) => <Tag color={row.status === '0' ? 'success' : 'default'}>{row.status === '0' ? '启用' : '停用'}</Tag> },
    { title: '更新时间', dataIndex: 'updateTime', width: 175, render: (value) => value || '—' },
    { title: '操作', fixed: 'right', width: 190, render: (_, row) => <Space>
      <Button type="link" icon={<EditOutlined />} disabled={!canQuery} onClick={() => requestOpenAgent(row)}>版本治理</Button>
      {canEdit && <Button type="link" loading={statusPendingIds.has(String(row.id))}
        disabled={statusPendingIds.has(String(row.id)) || (row.status === '1' && row.runtimeAvailability !== 'AVAILABLE')}
        onClick={() => void changeStatus(row, row.status === '0' ? '1' : '0')}>{row.status === '0' ? '停用' : '启用'}</Button>}
    </Space> }
  ];

  const editorBusy = draftLoading || saving || validating || publishing;
  const builtinBusy = builtinSkillsLoading || preparingBuiltinSkills;
  const moduleBindingsBusy = moduleBindingsLoading || Boolean(bindingModule);
  const selectedModuleBinding = moduleBindings.find((item) => item.expectedAgentCode === selected?.agentCode);

  const openVersionDetail = async (row: RuntimeAgentVersion) => {
    if (!selected) return;
    const agentId = selected.id;
    const request = ++versionDetailSequence.current;
    setVersionDetailLoadingId(row.id);
    try {
      const response = await getRuntimeAgentVersion(row.id, true);
      if (request !== versionDetailSequence.current || !isCurrentAgent(agentId)) return;
      setVersionDetail(response.data);
    } catch (cause: any) {
      if (request === versionDetailSequence.current && isCurrentAgent(agentId)) {
        message.error(cause?.message || '版本详情读取失败');
      }
    } finally {
      if (request === versionDetailSequence.current && isCurrentAgent(agentId)) {
        setVersionDetailLoadingId(undefined);
      }
    }
  };

  const versionColumns: ColumnsType<RuntimeAgentVersion> = [
    { title: '版本', dataIndex: 'versionCode', width: 120, render: (value, row) => <Space>{value}{row.current && <Tag color="blue">当前</Tag>}</Space> },
    { title: '冻结状态', dataIndex: 'publishStatus', width: 110, render: (value) => <Tag>{value}</Tag> },
    { title: '协议', dataIndex: 'protocolVersion', width: 150 },
    { title: '默认模型', dataIndex: 'defaultModelCode', width: 160 },
    { title: '运行可用性', width: 250, render: (_, row) => availability(row) },
    { title: '发布时间', dataIndex: 'createTime', width: 175, render: (value) => value || '—' },
    { title: '操作', fixed: 'right', width: 170, render: (_, row) => <Space>
      <Button type="link" icon={<EyeOutlined />}
        disabled={editorBusy}
        loading={String(versionDetailLoadingId) === String(row.id)}
        onClick={() => void openVersionDetail(row)}>详情</Button>
      {!row.current && <Button type="link" disabled={!canActivate || editorBusy} onClick={() => void activate(row)}>{selected?.currentVersionId ? '回滚/切换' : '激活'}</Button>}
      {row.current && selectedModuleBinding && <Button type="link" disabled={!canActivate || editorBusy
        || moduleBindingsBusy || selected?.status !== '0' || row.runtimeAvailability !== 'AVAILABLE'}
        loading={bindingModule === selectedModuleBinding.moduleCode}
        onClick={() => void bindModule(selectedModuleBinding, row)}>绑定模块</Button>}
    </Space> }
  ];

  const modelOptions = models.map((item) => ({
    value: item.modelCode, disabled: !item.available,
    label: `${item.modelName || item.modelCode}${item.providerName ? ` · ${item.providerName}` : ''}${item.available ? '' : `（${item.unavailableReason || '不可用'}）`}`
  }));
  const unresolvedSkillVersionIds = editorBindingResolution?.unresolvedSkillVersionIds || [];
  const unresolvedToolCodes = editorBindingResolution?.unresolvedToolCodes || [];
  const unresolvedSkillLabels = unresolvedSkillVersionIds.map((id) => {
    const binding = draft?.skillBindings?.find((item) => String(item.skillVersionId) === id);
    return `${binding?.skillCode || '未知 Skill'} · 版本 ID ${id}`;
  });
  const unresolvedToolLabels = unresolvedToolCodes.map((code) => {
    const binding = draft?.toolBindings?.find((item) => item.toolCode === code);
    return `${code}${binding?.contractVersion ? `@${binding.contractVersion}` : ''}`;
  });
  const skillOptions = [
    ...skills.map((item) => ({
      value: String(item.skillVersionId), label: `${item.name} · ${item.skillCode}@${item.versionCode}`
    })),
    ...unresolvedSkillVersionIds.map((id, index) => ({
      value: id, label: `不可用历史绑定 · ${unresolvedSkillLabels[index]}`
    }))
  ];
  const toolOptions = [
    ...tools.map((item) => ({
      value: item.code, label: `${item.name} · ${item.code}@${item.contractVersion}`
    })),
    ...unresolvedToolCodes.map((code, index) => ({
      value: code, label: `不可用历史绑定 · ${unresolvedToolLabels[index]}`
    }))
  ];
  const publishReady = Boolean(draft?.draftId && draft?.draftDigest && validation?.valid
    && validation.draftDigest === draft.draftDigest && !dirty && !editorBusy && editorBindingsReady);
  const builtinReadyText = builtinSkills
    ? `${builtinSkills.ready}/${builtinSkills.total}`
    : '--/--';

  if (!canList) return <Alert type="warning" showIcon message="没有Runtime Agent列表权限" />;
  return <div className="assistant-admin__panel">
    <Alert type="info" showIcon message="Runtime Agent与运营 aid_agent 相互独立。发布仅冻结配置；运行可用性由执行器、模型和固定绑定共同决定。" />
    <Card size="small" title="内置模板依赖">
      {canQuery && <Space direction="vertical" size={8} style={{ width: '100%', marginBottom: 12 }}>
        <Space wrap>
          <Tag color={!builtinSkills ? 'default'
            : builtinSkills.ready === builtinSkills.total ? 'success' : 'processing'}>
            已就绪 {builtinReadyText}
          </Tag>
          <Tag>待发布草稿 {builtinSkills ? builtinSkills.drafts : '--'}</Tag>
          <Tag>待处理 {builtinSkills ? builtinSkills.missing : '--'}</Tag>
          <Tag color={builtinSkills?.conflicts ? 'error' : 'default'}>
            冲突 {builtinSkills ? builtinSkills.conflicts : '--'}
          </Tag>
          <Button disabled={!canEdit || builtinBusy || Boolean(importingCode) || Boolean(builtinSkills?.conflicts)}
            loading={preparingBuiltinSkills}
            onClick={() => void prepareBuiltinSkills()}>准备缺失 Skill 草稿</Button>
          <Button disabled={builtinBusy} loading={builtinSkillsLoading}
            onClick={() => void loadBuiltinSkills(true)}>刷新依赖</Button>
        </Space>
        <Text type="secondary">此处显示内置模板固定版本的准备状态，不代表当前已发布主 Agent 的运行状态；实际状态见下方模块绑定。准备操作只创建停用身份和可审查草稿，请先校验、发布并启用所需版本。</Text>
        <Table size="small" rowKey="skillCode" loading={builtinSkillsLoading} pagination={{ pageSize: 8 }}
          dataSource={builtinSkills?.items || []} columns={[
            { title: 'Skill', dataIndex: 'name', render: (_: unknown, row: any) => <Space direction="vertical" size={0}><Text>{row.name}</Text><Text code>{row.skillCode}</Text></Space> },
            { title: '版本', dataIndex: 'versionCode', width: 90 },
            { title: '状态', dataIndex: 'state', width: 180, render: (value: string) => {
              const upgradeRequired = value === 'UPGRADE_REQUIRED';
              const label = upgradeRequired ? '需创建升级草稿' : value;
              const color = value === 'READY' ? 'success'
                : value.includes('CONFLICT') ? 'error' : upgradeRequired ? 'warning' : 'processing';
              return <Tag color={color}>{label}</Tag>;
            } },
            { title: '处理说明', dataIndex: 'message' }
          ]} />
      </Space>}
      <Space wrap>
        {BUILTIN_RUNTIME_AGENTS.map((agent) => <Button key={agent.code}
          disabled={!canEdit || builtinBusy || Boolean(importingCode)} loading={importingCode === agent.code}
          onClick={() => void importBuiltin(agent.code)}>导入{agent.name}草稿</Button>)}
        <Text type="secondary">导入只创建停用草稿；发布、切换当前版本、启用和模块绑定均需显式操作。</Text>
      </Space>
    </Card>
    {canQuery && <Card size="small" title="模块默认主 Agent">
      <Spin spinning={moduleBindingsLoading}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          {moduleBindings.map((binding) => <Descriptions key={binding.moduleCode} bordered size="small" column={{ xs: 1, sm: 4 }}>
            <Descriptions.Item label="模块">{binding.moduleCode}</Descriptions.Item>
            <Descriptions.Item label="目标身份"><Text code>{binding.expectedAgentCode}</Text></Descriptions.Item>
            <Descriptions.Item label="绑定版本">{binding.activeVersionId == null ? '未绑定' : String(binding.activeVersionId)}</Descriptions.Item>
            <Descriptions.Item label="修订/可用性">
              <Space><Text>{binding.bindingRevision}</Text><Tag color={binding.runtimeAvailability === 'AVAILABLE' ? 'success' : 'default'}>{binding.runtimeAvailability}</Tag></Space>
              {binding.runtimeUnavailableReason && <Text type="secondary">{binding.runtimeUnavailableReason}</Text>}
            </Descriptions.Item>
          </Descriptions>)}
        </Space>
      </Spin>
    </Card>}
    <Card size="small"><Form form={queryForm} layout="inline" onFinish={(values) => { setPageNum(1); setQuery(values); }}>
      <Form.Item name="keyword" label="关键字"><Input allowClear /></Form.Item>
      <Form.Item name="status" label="状态"><Select allowClear style={{ width: 130 }} options={[{ value: '0', label: '启用' }, { value: '1', label: '停用' }]} /></Form.Item>
      <Space><Button type="primary" htmlType="submit">查询</Button><Button onClick={() => { queryForm.resetFields(); setPageNum(1); setQuery({}); }}>重置</Button></Space>
    </Form></Card>
    {error && <Alert type="error" showIcon message="读取失败" description={error} />}
    <Card size="small" title="Runtime Agent" extra={<Space>
      <Button icon={<ReloadOutlined />} onClick={() => void load(true)}>刷新</Button>
      {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建 Agent</Button>}
    </Space>}>
      <Table rowKey={(row) => String(row.id)} columns={columns} dataSource={items} loading={loading} scroll={{ x: 1220 }}
        pagination={{ current: pageNum, pageSize, total, showSizeChanger: true, showTotal: (count) => `共 ${count} 个`, onChange: (page, size) => { setPageNum(page); setPageSize(size); } }} />
    </Card>

    <Modal title="新建 Runtime Agent" open={createOpen} confirmLoading={creating} onOk={() => void create()}
      onCancel={() => setCreateOpen(false)} destroyOnClose>
      <Form form={createForm} layout="vertical" initialValues={{ visibility: 'PRIVATE' }}>
        <Form.Item name="agentCode" label="Agent 编码" rules={[{ required: true }, { pattern: /^[a-z][a-z0-9._-]{1,63}$/ }]}><Input /></Form.Item>
        <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="description" label="说明"><Input.TextArea maxLength={1000} showCount /></Form.Item>
        <Form.Item name="visibility" label="可见性" rules={[{ required: true }]}><Select options={[{ value: 'PRIVATE', label: 'PRIVATE' }, { value: 'PUBLIC', label: 'PUBLIC' }]} /></Form.Item>
      </Form>
    </Modal>

    <Drawer title={selected ? `${selected.name} · 版本治理` : 'Agent版本治理'} width="min(1080px, 100vw)"
      open={Boolean(selected)} onClose={requestCloseAgent} destroyOnClose loading={draftLoading}
      maskClosable={!editorBusy} keyboard={!editorBusy}>
      {selected && <Tabs activeKey={drawerTab} onChange={setDrawerTab} items={[
        { key: 'draft', label: '配置草稿', children: <Space direction="vertical" size={16} className="assistant-admin__drawer-body">
          <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
            <Descriptions.Item label="执行类型"><Tag>ASSISTANT_LOOP</Tag></Descriptions.Item>
            <Descriptions.Item label="协议"><Tag>{draft?.protocolVersion || 'assistant-loop/native-v1'}</Tag></Descriptions.Item>
            <Descriptions.Item label="当前版本">{selected.currentVersionId == null ? '未激活' : String(selected.currentVersionId)}</Descriptions.Item>
            <Descriptions.Item label="运行可用性">{availability(selected)}</Descriptions.Item>
          </Descriptions>
          {draft?.packageVersionCode && <Alert
            type="info"
            showIcon
            message={`当前草稿固定为内置包版本 ${draft.packageVersionCode}`}
            description="按原版本发布时保持固定版本。需要发布下一版本时，请先显式转换；转换会保存当前全部草稿内容，解除版本锁，并使旧校验结果失效。"
            action={canEdit ? <Button
              size="small"
              loading={saving}
              disabled={editorBusy || !editorCatalogReady || !editorBindingsReady}
              onClick={requestConvertToFollowupVersion}
            >转为后续版本</Button> : undefined}
          />}
          <Form form={editorForm} layout="vertical" disabled={!canEdit || editorBusy || !editorCatalogReady} onValuesChange={() => {
            editorRevision.current += 1;
            setDirty(true);
            setValidation(undefined);
            setValidationError('');
          }}>
            <Card size="small" title="Agent 文件包">
              <Form.Item name="systemPrompt" label="SP.md" rules={[{ required: true }, { max: 100000 }]}
                extra="Agent 的唯一入口正文，与参考文件及运行配置一起冻结。">
                <Input.TextArea autoSize={{ minRows: 12, maxRows: 28 }} showCount maxLength={100000} />
              </Form.Item>
              <Form.List name="resources">
                {(fields, { add, remove }) => <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  {fields.map((field) => <Card key={field.key} size="small" title={`参考文件 ${field.name + 1}`}
                    extra={<Popconfirm title="从当前草稿删除这个参考文件？" onConfirm={() => remove(field.name)}>
                      <Button type="text" danger icon={<DeleteOutlined />} aria-label="删除参考文件" />
                    </Popconfirm>}>
                    <Form.Item name={[field.name, 'resourceKey']} label="资源键"
                      rules={[{ required: true }, { pattern: /^[a-z0-9][a-z0-9._-]{0,99}$/, message: '使用小写字母、数字、点、下划线或连字符' }]}>
                      <Input maxLength={100} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'filePath']} label="逻辑路径"
                      rules={[{ required: true }, { validator: (_, value) => isSafeReferencePath(value)
                        ? Promise.resolve()
                        : Promise.reject(new Error('路径必须位于 references/，且为不超过 192 字符的安全小写相对路径')) }]}>
                      <Input maxLength={MAX_REFERENCE_PATH_LENGTH} placeholder="references/example.md" />
                    </Form.Item>
                    <Form.Item name={[field.name, 'mimeType']} label="内容类型" rules={[{ required: true }]}>
                      <Select options={[{ value: 'text/markdown', label: 'Markdown' }, { value: 'text/plain', label: '纯文本' }, { value: 'application/json', label: 'JSON' }]} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'content']} label="内容" rules={[{ required: true }, { max: 102400 }]}>
                      <Input.TextArea autoSize={{ minRows: 6, maxRows: 20 }} showCount maxLength={102400} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'routeJson']} hidden><Input /></Form.Item>
                  </Card>)}
                  <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({
                    resourceKey: '', filePath: 'references/', mimeType: 'text/markdown', content: '', routeJson: '{}'
                  })}>新建参考文件</Button>
                </Space>}
              </Form.List>
            </Card>
            <Card size="small" title="模型能力与既有计费配置引用">
              <RuntimeAgentResponsePolicyFields />
              <Form.Item name="modelCapabilityCode" label="能力协议"><Select disabled options={[{ value: 'text.chat', label: 'text.chat' }]} /></Form.Item>
              <Form.Item name="selectableModelCodes" label="允许模型" rules={[{ required: true, type: 'array', min: 1 }]}>
                <Select mode="multiple" showSearch optionFilterProp="label" options={modelOptions}
                  onChange={(codes: string[]) => {
                    const currentDefault = editorForm.getFieldValue('defaultModelCode');
                    if (!codes.includes(currentDefault)) {
                      editorForm.setFieldValue('defaultModelCode', codes.find((code) => models.some((model) => model.modelCode === code && model.available)));
                    }
                    setValidation(undefined);
                    setValidationError('');
                  }} />
              </Form.Item>
              <Form.Item name="defaultModelCode" label="默认模型" dependencies={['selectableModelCodes']} rules={[{ required: true }]}>
                <Select showSearch optionFilterProp="label" options={modelOptions.filter((option) => (selectableModelCodes || []).includes(option.value))} />
              </Form.Item>
              <Paragraph type="secondary">费用与能力来自既有模型、Provider 和 SKU 配置；此处不复制价格。</Paragraph>
            </Card>
            <Card size="small" title="固定 Skill 与 Tool 允许范围">
              {!editorBindingsReady && editorCatalogReady && (
                <Alert
                  type="warning"
                  showIcon
                  message="存在当前目录无法解析的历史绑定"
                  description={(
                    <div>
                      <div>服务端草稿尚未改变。请在对应选择框中显式移除以下不可用项后再保存或校验：</div>
                      {unresolvedSkillLabels.length > 0 && (
                        <div>
                          <Text strong>Skill：</Text>
                          {unresolvedSkillLabels.map((label) => (
                            <Tag key={label} color="warning" style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
                              {label}
                            </Tag>
                          ))}
                        </div>
                      )}
                      {unresolvedToolLabels.length > 0 && (
                        <div>
                          <Text strong>Tool：</Text>
                          {unresolvedToolLabels.map((label) => (
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
              <Form.Item
                name="selectedSkillVersionIds"
                label="固定 Skill 版本"
                validateStatus={unresolvedSkillVersionIds.length > 0 ? 'error' : undefined}
                help={unresolvedSkillVersionIds.length > 0 ? '请显式移除标记为“不可用历史绑定”的 Skill 版本' : undefined}
              >
                <Select mode="multiple" showSearch optionFilterProp="label" options={skillOptions} />
              </Form.Item>
              <Form.Item
                name="selectedToolCodes"
                label="允许 Tool"
                validateStatus={unresolvedToolCodes.length > 0 ? 'error' : undefined}
                help={unresolvedToolCodes.length > 0 ? '请显式移除标记为“不可用历史绑定”的 Tool' : undefined}
              >
                <Select mode="multiple" showSearch optionFilterProp="label" options={toolOptions} />
              </Form.Item>
              <Paragraph type="secondary">发布时服务端再次校验 Skill 包摘要和 Tool 契约摘要。这里只允许选择服务端注册的受控 Tool。</Paragraph>
            </Card>
            <Card size="small" title="循环与检查点边界">
              <Space wrap align="start">
                <Form.Item name="maxTurns" label="最多回合" rules={[{ required: true }]}><InputNumber min={1} max={64} /></Form.Item>
                <Form.Item name="maxToolCalls" label="最多 Tool 调用" rules={[{ required: true }]}><InputNumber min={1} max={64} /></Form.Item>
                <Form.Item name="temperatureBasisPoints" label="温度基点" rules={[{ required: true }]}><InputNumber min={0} max={200} /></Form.Item>
                <Form.Item name="checkpointPolicy" label="检查点策略" rules={[{ required: true }]}><Select style={{ width: 180 }} options={[{ value: 'TURN_BOUNDARY', label: '回合边界' }, { value: 'TOOL_BOUNDARY', label: 'Tool边界' }]} /></Form.Item>
              </Space>
            </Card>
            <Card size="small" title="输入参数"><Form.Item name="inputSchemaJson" noStyle><ContractEditor
              key={`${selected.id}:${draft?.baseVersionId}:${draft?.draftId}`} direction="输入"
              disabled={!canEdit || editorBusy || !editorCatalogReady} /></Form.Item></Card>
            <Card size="small" title="输出参数"><Form.Item name="outputSchemaJson" noStyle><ContractEditor
              key={`${selected.id}:${draft?.baseVersionId}:${draft?.draftId}`} direction="输出"
              disabled={!canEdit || editorBusy || !editorCatalogReady} /></Form.Item></Card>
          </Form>
          {validationError && <Alert type="error" showIcon message="配置校验未完成"
            description={validationError} />}
          {validation && <Alert type={validation.valid ? 'success' : 'error'} showIcon
            message={validation.valid ? '发布校验通过' : '发布校验未通过'}
            description={<Space direction="vertical" size={2}>{validation.errors.map((item) => <Text key={`${item.field}:${item.message}`} type="danger">{item.field}：{item.message}</Text>)}{validation.warnings.map((item) => <Text key={`${item.field}:${item.message}`} type="warning">{item.field}：{item.message}</Text>)}</Space>} />}
          <Space>
            {canEdit && <Button type="primary" icon={<SaveOutlined />} loading={saving}
              disabled={editorBusy || !editorCatalogReady || !editorBindingsReady}
              onClick={() => void save()}>保存草稿</Button>}
            {canEdit && <Button loading={validating}
              disabled={editorBusy || !editorCatalogReady || !editorBindingsReady}
              onClick={() => void validate()}>校验</Button>}
            {canPublish && <Button icon={<RocketOutlined />} disabled={!publishReady} onClick={() => {
              publishForm.setFieldsValue({ versionCode: draft?.packageVersionCode || '' });
              setPublishOpen(true);
            }}>冻结发布</Button>}
          </Space>
        </Space> },
        { key: 'versions', label: '不可变版本', children: <Table rowKey={(row) => String(row.id)} columns={versionColumns} dataSource={versions}
          scroll={{ x: 1080 }} pagination={{ current: versionPage, pageSize: versionSize, total: versionTotal, showSizeChanger: true,
            disabled: editorBusy,
            onChange: (page, size) => { setVersionPage(page); setVersionSize(size); void loadVersions(selected.id, page, size); } }} /> }
      ]} />}
    </Drawer>

    <Modal title="冻结发布 Agent 版本" open={publishOpen} confirmLoading={publishing} onOk={() => void publish()}
      onCancel={() => { if (!publishing) setPublishOpen(false); }} destroyOnClose
      okButtonProps={{ disabled: publishing }} cancelButtonProps={{ disabled: publishing }} maskClosable={!publishing}>
      <Alert type="info" showIcon message="发布生成不可变快照，不代表运行时已接通，也不会自动启用 Agent。" />
      <Form form={publishForm} layout="vertical" style={{ marginTop: 16 }}>
        <Form.Item name="versionCode" label="版本号"
          extra={draft?.packageVersionCode ? '内置包草稿保持固定版本；需要新版本时，请先在草稿区执行“转为后续版本”。' : undefined}
          rules={[{ required: true }, { pattern: SEMVER, message: '请输入 SemVer，例如 1.0.0' }]}>
          <Input placeholder="1.0.0" disabled={Boolean(draft?.packageVersionCode)} />
        </Form.Item>
      </Form>
    </Modal>

    <Drawer title={`版本详情 · ${versionDetail?.versionCode || ''}`} width="min(920px, 100vw)" open={Boolean(versionDetail)}
      onClose={closeVersionDetail} destroyOnClose>
      {versionDetail && <Space direction="vertical" size={16} className="assistant-admin__drawer-body">
        <Alert type="info" showIcon message="Agent 文件包读取已记录内容脱敏的后台审计。" />
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="版本 ID">{String(versionDetail.id)}</Descriptions.Item>
          <Descriptions.Item label="包摘要"><Text code>{versionDetail.packageDigest}</Text></Descriptions.Item>
          <Descriptions.Item label="执行类型">{versionDetail.executorType}</Descriptions.Item>
          <Descriptions.Item label="协议">{versionDetail.protocolVersion}</Descriptions.Item>
          <Descriptions.Item label="模型能力">{versionDetail.modelCapabilityCode}</Descriptions.Item>
          <Descriptions.Item label="默认模型">{versionDetail.defaultModelCode}</Descriptions.Item>
          <Descriptions.Item label="固定 Skill" span={2}>{versionDetail.skillBindings.map((item) => `${item.skillCode}#${item.skillVersionId}`).join('、') || '无'}</Descriptions.Item>
          <Descriptions.Item label="允许 Tool" span={2}>{versionDetail.toolBindings.map((item) => `${item.toolCode}@${item.contractVersion}`).join('、') || '无'}</Descriptions.Item>
        </Descriptions>
        <Title level={5}>SP.md</Title><Paragraph className="assistant-admin__prompt-view">{versionDetail.systemPrompt}</Paragraph>
        <Card size="small" title="输入参数">
          <ContractEditor value={versionDetail.inputSchemaJson} direction="输入" disabled />
        </Card>
        <Card size="small" title="输出参数">
          <ContractEditor value={versionDetail.outputSchemaJson} direction="输出" disabled />
        </Card>
        <Title level={5}>references</Title>
        {versionDetail.resources.map((resource) => <Card key={`${resource.resourceKey}:${resource.filePath}`} size="small"
          title={resource.filePath} extra={<Text code>{resource.resourceKey}</Text>}>
          <Paragraph className="assistant-admin__prompt-view">{resource.content}</Paragraph>
        </Card>)}
      </Space>}
    </Drawer>
  </div>;
}
