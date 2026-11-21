import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Avatar, Button, Card, Descriptions, Divider, Drawer, Empty, Form, Input,
  InputNumber, List, Modal, Popconfirm, Radio, Select, Space, Switch, Table, Tag,
  Typography, message } from 'antd';
import type { FormInstance } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowDownOutlined, ArrowUpOutlined, CheckCircleOutlined, DeleteOutlined,
  EyeOutlined, HistoryOutlined, PlusOutlined, ReloadOutlined,
  RocketOutlined, SaveOutlined, SearchOutlined } from '@ant-design/icons';
import { activateRuntimeAgentVersion, createRuntimeAgent, getRuntimeAgentDraft,
  getRuntimeAgentRun, getRuntimeAgentVersion, listRuntimeAgentModelOptions,
  listRuntimeAgentRuns, listRuntimeAgentSkillOptions, listRuntimeAgentToolOptions, listRuntimeAgentVersions,
  listRuntimeAgents, publishRuntimeAgentDraft, removeRuntimeAgent, saveRuntimeAgentDraft,
  setRuntimeAgentStatus, updateRuntimeAgentIdentity, validateRuntimeAgentDraft,
  type RuntimeAgentDraft, type RuntimeAgentDraftPayload, type RuntimeAgentRun,
  type RuntimeAgentStep, type RuntimeAgentSummary, type RuntimeAgentValidation,
  type RuntimeAgentSkillOption, type RuntimeAgentVersion, type RuntimeId,
  type RuntimeTryRunRecordTarget } from '@/api/aid/legacyRuntimePlanAgent';
import { getSkillRun, type RegisteredToolOption, type SkillRunItem,
  type SkillTextModelOption } from '@/api/aid/skill';
import { listBusinessGroups, saveBusinessGroupMember, type BusinessGroup } from '@/api/aid/businessGroup';
import { showReferenceConflict } from '@/utils/apiErrorDetails';
import { wasRequestErrorReported } from '@/utils/request';
import StructuredValueEditor from '@/views/aid/skill/StructuredValueEditor';
import TryRunModal from './TryRunModal';
import RuntimeRunMedia from './RuntimeRunMedia';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';

const SEMVER = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:[-+][A-Za-z0-9.-]+)?$/;

function serialized(value: unknown) {
  if (typeof value === 'string') {
    try { JSON.parse(value); return value; } catch { return JSON.stringify(value); }
  }
  return JSON.stringify(value ?? {});
}

function billingStatusTag(value?: string) {
  if (!value) return '--';
  const descriptions: Record<string, string> = {
    INIT: '尚未完成预冻结', FROZEN: '费用已预冻结，等待任务收口',
    SETTLING: '正在结算', REFUNDING: '正在退回预冻结费用',
    SUCCESS: '结算成功', FAILED: '预冻结失败，或任务失败后费用已退回'
  };
  const colors: Record<string, string> = {
    FROZEN: 'gold', SETTLING: 'processing', REFUNDING: 'warning',
    SUCCESS: 'success', FAILED: 'error'
  };
  return <Tag color={colors[value]} title={descriptions[value]}>{value}</Tag>;
}

function modelLabel(model: SkillTextModelOption) {
  const billing = model.billing as { isFree?: boolean; billingDesc?: string } | undefined;
  return <Space size={6}><Avatar size="small" src={model.modelLogo || model.providerLogo} />
    <span>{model.modelName || model.modelCode}</span>
    <Typography.Text type="secondary">{model.providerName || model.modelCode}</Typography.Text>
    <Tag color={billing?.isFree ? 'success' : undefined}>{billing?.isFree ? '免费' : billing?.billingDesc || '价格未配置'}</Tag>
  </Space>;
}

function StepFields({ name, form, skillOptions, toolOptions, remove, move, index, count }: {
  name: number;
  form: FormInstance<RuntimeAgentDraftPayload>;
  skillOptions: { label: string; value: string }[];
  toolOptions: { label: string; value: string }[];
  remove: () => void;
  move: (to: number) => void;
  index: number;
  count: number;
}) {
  const kind = Form.useWatch(['steps', name, 'kind'], form) as RuntimeAgentStep['kind'];
  const allowedSkills = Form.useWatch('allowedSkillCodes', form) || [];
  const allowedTools = Form.useWatch('allowedToolCodes', form) || [];
  return <Card size="small" title={`步骤 ${index + 1}`} style={{ marginBottom: 10 }}
    extra={<Space size={0}>
      <Button type="text" aria-label="上移步骤" icon={<ArrowUpOutlined />}
        disabled={index === 0} onClick={() => move(index - 1)} />
      <Button type="text" aria-label="下移步骤" icon={<ArrowDownOutlined />}
        disabled={index >= count - 1} onClick={() => move(index + 1)} />
      <Button type="text" danger aria-label="移除步骤" icon={<DeleteOutlined />} onClick={remove} />
    </Space>}>
    <Space wrap align="start">
      <Form.Item name={[name, 'stepKey']} label="步骤标识" rules={[{ required: true },
        { pattern: /^[a-z][a-z0-9._-]{0,63}$/, message: '以小写字母开头，不超过 64 位' }]}>
        <Input placeholder="例如 draft" maxLength={64} />
      </Form.Item>
      <Form.Item name={[name, 'kind']} label="执行方式" rules={[{ required: true }]}>
        <Select style={{ width: 130 }} options={[{ value: 'MODEL', label: '模型' },
          { value: 'TOOL', label: '已注册 Tool' }, { value: 'SKILL', label: 'Skill' }]}
          onChange={() => { form.setFieldValue(['steps', name, 'toolCode'], undefined);
            form.setFieldValue(['steps', name, 'skillCode'], undefined); }} />
      </Form.Item>
      {kind === 'TOOL' && <Form.Item name={[name, 'toolCode']} label="Tool"
        rules={[{ required: true, message: '请选择已注册 Tool' }]}>
        <Select style={{ minWidth: 220 }} showSearch optionFilterProp="label"
          options={toolOptions.filter((item) => allowedTools.includes(item.value))} />
      </Form.Item>}
      {kind === 'SKILL' && <Form.Item name={[name, 'skillCode']} label="Skill"
        rules={[{ required: true, message: '请选择允许的 Skill' }]}>
        <Select style={{ minWidth: 220 }} showSearch optionFilterProp="label"
          options={skillOptions.filter((item) => allowedSkills.includes(item.value))} />
      </Form.Item>}
    </Space>
    <Form.Item name={[name, 'instruction']} label="本步骤指令">
      <Input.TextArea autoSize={{ minRows: 2, maxRows: 8 }} maxLength={10000} showCount />
    </Form.Item>
  </Card>;
}

export default function RuntimeAgentPage() {
  const { hasPermi } = useAuth();
  const canList = hasPermi('aid:runtimeAgent:query');
  const canQuery = hasPermi('aid:runtimeAgent:query');
  const canEdit = hasPermi('aid:runtimeAgent:edit');
  const canEditGroups = hasPermi('aid:businessGroup:query') && hasPermi('aid:businessGroup:edit');
  const [form] = Form.useForm<RuntimeAgentDraftPayload>();
  const [createForm] = Form.useForm();
  const [identityForm] = Form.useForm();
  const [publishForm] = Form.useForm<{ versionCode: string }>();
  const [records, setRecords] = useState<RuntimeAgentSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [query, setQuery] = useState<{ keyword?: string; status?: '0' | '1' }>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [createMode, setCreateMode] = useState<'blank' | 'template'>('blank');
  const [createGroups, setCreateGroups] = useState<BusinessGroup[]>([]);
  const [templateOptions, setTemplateOptions] = useState<{ value: RuntimeId; label: string }[]>([]);
  const [templateLoading, setTemplateLoading] = useState(false);
  const templateSequence = useRef(0);
  const templateTimer = useRef<ReturnType<typeof setTimeout>>();
  const [creating, setCreating] = useState(false);
  const [identityOpen, setIdentityOpen] = useState<RuntimeAgentSummary>();
  const [identitySaving, setIdentitySaving] = useState(false);
  const [selected, setSelected] = useState<RuntimeAgentSummary>();
  const [draft, setDraft] = useState<RuntimeAgentDraft>();
  const [draftLoading, setDraftLoading] = useState(false);
  const [draftError, setDraftError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [validation, setValidation] = useState<RuntimeAgentValidation>();
  const [publishing, setPublishing] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [versions, setVersions] = useState<RuntimeAgentVersion[]>([]);
  const [versionTotal, setVersionTotal] = useState(0);
  const [versionPage, setVersionPage] = useState(1);
  const [versionSize, setVersionSize] = useState(20);
  const [versionDetail, setVersionDetail] = useState<RuntimeAgentVersion>();
  const [activeTab, setActiveTab] = useState('draft');
  const [models, setModels] = useState<SkillTextModelOption[]>([]);
  const [skills, setSkills] = useState<RuntimeAgentSkillOption[]>([]);
  const [tools, setTools] = useState<RegisteredToolOption[]>([]);
  const [runs, setRuns] = useState<RuntimeAgentRun[]>([]);
  const [runTotal, setRunTotal] = useState(0);
  const [runPage, setRunPage] = useState(1);
  const [runSize, setRunSize] = useState(20);
  const [runLoading, setRunLoading] = useState(false);
  const [runDetail, setRunDetail] = useState<RuntimeAgentRun>();
  const [delegatedRunId, setDelegatedRunId] = useState<RuntimeId>();
  const [delegatedRunDetail, setDelegatedRunDetail] = useState<SkillRunItem>();
  const [delegatedRunLoading, setDelegatedRunLoading] = useState(false);
  const [delegatedRunError, setDelegatedRunError] = useState('');
  const [tryRunOpen, setTryRunOpen] = useState(false);
  const listSeq = useRef(0);
  const detailSeq = useRef(0);
  const runSeq = useRef(0);
  const delegatedRunSeq = useRef(0);
  const busy = useRef(false);

  const load = useCallback(async (force = false) => {
    if (!canList) return;
    const seq = ++listSeq.current;
    setLoading(true); setError('');
    try {
      const response = await listRuntimeAgents({ pageNum: page, pageSize: size, ...query }, force);
      if (seq !== listSeq.current) return;
      setRecords(response.data || []);
      setTotal(Number(response.total || 0));
    } catch (cause: any) {
      if (seq === listSeq.current) setError(cause?.message || 'Agent 列表加载失败');
    } finally { if (seq === listSeq.current) setLoading(false); }
  }, [canList, page, query, size]);
  useEffect(() => { void load(); }, [load]);

  const loadGroups = async () => {
    try {
      const rows: BusinessGroup[] = [];
      for (let pageNum = 1; pageNum <= 100; pageNum += 1) {
        const response = await listBusinessGroups({ pageNum, pageSize: 100 });
        rows.push(...response.data || []);
        if (rows.length >= Number(response.total || 0) || (response.data || []).length < 100) break;
      }
      setCreateGroups(rows);
    } catch { setCreateGroups([]); }
  };

  const searchTemplates = async (keyword = '') => {
    const sequence = ++templateSequence.current;
    setTemplateLoading(true);
    try {
      const response = await listRuntimeAgents({ pageNum: 1, pageSize: 100,
        keyword: keyword || undefined });
      if (sequence !== templateSequence.current) return;
      setTemplateOptions((response.data || []).filter((item) => item.delFlag !== '1' && item.currentVersionId)
        .map((item) => ({ value: item.currentVersionId!,
          label: `${item.name} · ${item.agentCode}（版本 #${item.currentVersionId}）` })));
    } catch (cause: any) {
      if (sequence === templateSequence.current) message.error(cause?.message || '模板加载失败');
    } finally {
      if (sequence === templateSequence.current) setTemplateLoading(false);
    }
  };

  const openCreate = () => {
    setCreateMode('blank');
    createForm.setFieldsValue({ visibility: 'PRIVATE' });
    setCreateOpen(true);
    if (canEditGroups) void loadGroups();
  };
  const create = async () => {
    if (busy.current) return;
    busy.current = true; setCreating(true);
    try {
      const values = await createForm.validateFields();
      const { groupIds, ...identity } = values;
      const response = await createRuntimeAgent({ ...identity,
        templateVersionId: createMode === 'template' ? values.templateVersionId : undefined });
      let failedGroups = 0;
      for (const groupId of (canEditGroups ? groupIds || [] : []) as RuntimeId[]) {
        try { await saveBusinessGroupMember({ groupId, memberType: 'AGENT',
          memberId: response.data.id, sortOrder: 0 }); }
        catch { failedGroups += 1; }
      }
      if (failedGroups) message.warning(`${failedGroups} 个业务分组关联失败，可在分组页补齐`);
      message.success('Agent 已创建，可继续配置草稿');
      setCreateOpen(false);
      createForm.resetFields();
      await load();
      await openDetail(response.data);
    } catch (cause: any) {
      if (!cause?.errorFields) message.error(cause?.message || 'Agent 创建失败');
    } finally { busy.current = false; setCreating(false); }
  };

  const openIdentity = (row: RuntimeAgentSummary) => {
    setIdentityOpen(row);
    identityForm.setFieldsValue({ name: row.name, description: row.description,
      iconUrl: row.iconUrl, visibility: row.visibility || 'PRIVATE' });
  };
  const saveIdentity = async () => {
    if (!identityOpen || busy.current) return;
    busy.current = true; setIdentitySaving(true);
    try {
      const values = await identityForm.validateFields();
      await updateRuntimeAgentIdentity({ id: identityOpen.id, ...values });
      message.success('Agent 信息已保存');
      setIdentityOpen(undefined); await load();
    } catch (cause: any) {
      if (!cause?.errorFields) message.error(cause?.message || '保存失败');
    } finally { busy.current = false; setIdentitySaving(false); }
  };

  const loadVersions = async (agentId: RuntimeId, nextPage = 1, nextSize = 20) => {
    const response = await listRuntimeAgentVersions(agentId, nextPage, nextSize);
    setVersions(response.data || []);
    setVersionTotal(Number(response.total || 0));
    setVersionPage(nextPage); setVersionSize(nextSize);
  };
  const loadOptions = async () => {
    const [modelResponse, skillResponse, toolResponse] = await Promise.all([
      listRuntimeAgentModelOptions(), listRuntimeAgentSkillOptions(), listRuntimeAgentToolOptions()
    ]);
    setModels(modelResponse.data || []);
    setSkills(skillResponse.data || []);
    setTools((toolResponse.data || []).filter((item) => item.enabled !== false)
      .map((item) => ({ ...item, toolCode: item.toolCode || item.code || '' }))
      .filter((item) => item.toolCode));
  };
  const openDetail = async (row: RuntimeAgentSummary, force = false) => {
    setSelected(row); setActiveTab('draft'); setDraft(undefined); setValidation(undefined);
    setDirty(false); setDraftError('');
    const seq = ++detailSeq.current;
    setDraftLoading(true);
    try {
      const [response] = await Promise.all([
        getRuntimeAgentDraft(row.id, undefined, force), loadVersions(row.id), loadOptions()
      ]);
      if (seq !== detailSeq.current) return;
      setDraft(response.data);
      form.setFieldsValue({ ...response.data,
        outputKind: response.data.outputKind || 'TEXT',
        executorType: response.data.executorType || 'MODEL_PLAN',
        selectableModelCodes: response.data.selectableModelCodes || [],
        allowedSkillCodes: response.data.allowedSkillCodes || [],
        allowedToolCodes: response.data.allowedToolCodes || [],
        steps: response.data.steps || [] });
    } catch (cause: any) {
      if (seq === detailSeq.current) setDraftError(cause?.message || 'Agent 草稿加载失败');
    } finally { if (seq === detailSeq.current) setDraftLoading(false); }
  };
  const reloadDetail = () => selected && openDetail(selected, true);

  const payload = async () => {
    if (!selected || !draft) throw new Error('草稿未加载');
    const values = await form.validateFields();
    if (values.executorType === 'SKILL_ORCHESTRATOR' && !values.delegatedSkillCode) {
      form.setFields([{ name: 'delegatedSkillCode', errors: ['请选择已发布的编排 Skill'] }]);
      throw new Error('请选择已发布的编排 Skill');
    }
    if (!values.selectableModelCodes?.includes(values.defaultModelCode)) {
      form.setFields([{ name: 'defaultModelCode', errors: ['默认模型必须属于可选模型'] }]);
      throw new Error('默认模型必须属于可选模型');
    }
    const available = new Set(models.filter((item) => item.available).map((item) => item.modelCode));
    if (values.selectableModelCodes.some((code) => !available.has(code))) {
      form.setFields([{ name: 'selectableModelCodes', errors: ['可选模型中有停用或不可用模型'] }]);
      throw new Error('可选模型中有停用或不可用模型');
    }
    if (values.selectableModelCodes.some((code) => {
      const model = models.find((item) => item.modelCode === code);
      return model?.modelType && model.modelType.toUpperCase() !== values.outputKind;
    })) {
      form.setFields([{ name: 'selectableModelCodes', errors: ['可选模型与输出类型不一致'] }]);
      throw new Error('可选模型与输出类型不一致');
    }
    if (values.steps.some((step) => step.kind === 'TOOL' && !values.allowedToolCodes.includes(step.toolCode || '')
      || step.kind === 'SKILL' && !values.allowedSkillCodes.includes(step.skillCode || ''))) {
      form.setFields([{ name: 'steps', errors: ['步骤中的 Tool 或 Skill 必须先加入允许列表'] }]);
      throw new Error('步骤中选择的 Tool 或 Skill 必须在允许列表中');
    }
    return { ...values, agentId: selected.id, baseVersionId: draft.baseVersionId,
      delegatedSkillCode: values.executorType === 'SKILL_ORCHESTRATOR' ? values.delegatedSkillCode : undefined,
      steps: values.executorType === 'SKILL_ORCHESTRATOR' ? [] : values.steps,
      selectableModelCodes: [...new Set(values.selectableModelCodes)],
      allowedSkillCodes: values.executorType === 'SKILL_ORCHESTRATOR' ? [] : [...new Set(values.allowedSkillCodes)],
      allowedToolCodes: values.executorType === 'SKILL_ORCHESTRATOR' ? [] : [...new Set(values.allowedToolCodes)] };
  };
  const saveDraft = async () => {
    if (busy.current) return;
    busy.current = true; setSaving(true);
    try {
      const values = await payload();
      const response = await saveRuntimeAgentDraft({ ...values,
        draftId: draft?.draftId, draftDigest: draft?.draftDigest });
      setDraft(response.data); setDirty(false); setValidation(undefined);
      message.success('草稿已保存，当前执行版本未改变');
    } catch (cause: any) {
      if (!cause?.errorFields) message.error(cause?.message || '草稿保存失败');
    } finally { busy.current = false; setSaving(false); }
  };
  const validateDraft = async () => {
    if (busy.current) return;
    busy.current = true; setValidating(true);
    try {
      const response = await validateRuntimeAgentDraft(await payload());
      setValidation(response.data);
      if (response.data.valid) message.success('校验通过');
    } catch (cause: any) {
      if (!cause?.errorFields) message.error(cause?.message || '校验失败');
    } finally { busy.current = false; setValidating(false); }
  };
  const canPublish = !!draft?.draftId && !!draft.draftDigest && !dirty
    && validation?.valid === true && validation.draftDigest === draft.draftDigest;
  const publishDraft = async () => {
    if (!canPublish || busy.current || !draft?.draftId || !draft.draftDigest || !selected) return;
    busy.current = true; setPublishing(true);
    try {
      const { versionCode } = await publishForm.validateFields();
      await publishRuntimeAgentDraft(draft.draftId, draft.draftDigest, versionCode.trim());
      message.success('版本已发布；请在版本页显式切换');
      setPublishOpen(false); setTryRunOpen(false); publishForm.resetFields();
      await loadVersions(selected.id);
      await load();
    } catch (cause: any) {
      if (!cause?.errorFields) message.error(cause?.message || '发布失败');
    } finally { busy.current = false; setPublishing(false); }
  };
  const activate = async (row: RuntimeAgentVersion) => {
    if (!selected || busy.current) return;
    busy.current = true;
    try {
      await activateRuntimeAgentVersion(selected.id, row.id, selected.currentVersionId ?? null);
      message.success(`已切换至 ${row.versionCode}`);
      setSelected({ ...selected, currentVersionId: row.id });
      await loadVersions(selected.id, versionPage, versionSize);
      await load();
    } catch (cause: any) { message.error(cause?.message || '切换失败'); }
    finally { busy.current = false; }
  };
  const changeStatus = async (row: RuntimeAgentSummary, status: '0' | '1') => {
    if (busy.current) return;
    busy.current = true;
    try { await setRuntimeAgentStatus(row.id, status); await load(); }
    catch (cause: any) { message.error(cause?.message || '状态更新失败'); }
    finally { busy.current = false; }
  };
  const remove = async (row: RuntimeAgentSummary) => {
    if (busy.current) return;
    busy.current = true;
    try { await removeRuntimeAgent(row.id); message.success('Agent 已下线'); await load(); }
    catch (cause: any) {
      if (!showReferenceConflict(cause, 'Agent 仍有有效引用')) message.error(cause?.message || '删除失败');
    } finally { busy.current = false; }
  };

  const loadRuns = async (agentId: RuntimeId, nextPage = 1, nextSize = 20) => {
    const seq = ++runSeq.current; setRunLoading(true);
    try {
      const response = await listRuntimeAgentRuns({ agentId, pageNum: nextPage, pageSize: nextSize });
      if (seq !== runSeq.current) return;
      setRuns(response.data || []); setRunTotal(Number(response.total || 0));
      setRunPage(nextPage); setRunSize(nextSize);
    } catch (cause: any) { if (seq === runSeq.current) message.error(cause?.message || '运行记录加载失败'); }
    finally { if (seq === runSeq.current) setRunLoading(false); }
  };
  const showRun = async (id: RuntimeId) => {
    try { const response = await getRuntimeAgentRun(id); setRunDetail(response.data); }
    catch (cause: any) { message.error(cause?.message || '运行详情加载失败'); }
  };
  const closeDelegatedRun = () => {
    delegatedRunSeq.current += 1;
    setDelegatedRunLoading(false);
    setDelegatedRunError('');
    setDelegatedRunDetail(undefined);
    setDelegatedRunId(undefined);
  };
  const showDelegatedRun = async (id: RuntimeId) => {
    const request = ++delegatedRunSeq.current;
    setDelegatedRunId(id);
    setDelegatedRunDetail(undefined);
    setDelegatedRunLoading(true);
    setDelegatedRunError('');
    try {
      const response = await getSkillRun(id, { force: true });
      if (request !== delegatedRunSeq.current) return;
      setDelegatedRunDetail(response.data);
    } catch (cause: any) {
      if (request === delegatedRunSeq.current) {
        const text = cause?.message || '委托 Skill Run 详情加载失败';
        setDelegatedRunError(text);
        if (!wasRequestErrorReported(cause)) message.error(text);
      }
    } finally {
      if (request === delegatedRunSeq.current) setDelegatedRunLoading(false);
    }
  };
  const openTryRunRecords = (target: RuntimeTryRunRecordTarget) => {
    if (target.kind === 'SKILL_ORCHESTRATOR') {
      if (!hasPermi('aid:skill:run:query')) {
        message.warning('没有 Skill Run 详情权限，已保留可复制的 Legacy Run ID');
        return;
      }
      setTryRunOpen(false);
      void showDelegatedRun(target.runId);
      return;
    }
    if (target.memberType !== 'AGENT') {
      message.error('Agent 草稿返回了不匹配的通用运行记录类型');
      return;
    }
    const canListRuns = hasPermi('aid:runtimeAgent:run:list');
    const canQueryRun = hasPermi('aid:runtimeAgent:run:query');
    if (!canListRuns && !canQueryRun) {
      message.warning('没有 Runtime Agent 运行记录权限');
      return;
    }
    setTryRunOpen(false);
    setActiveTab('runs');
    if (canListRuns && selected) {
      void loadRuns(selected.id).then(() => {
        if (canQueryRun) void showRun(target.runId);
      });
    } else if (canQueryRun) {
      void showRun(target.runId);
    }
  };
  const skillOptions = skills.map((item) => ({ value: item.skillCode,
    label: `${item.name} · ${item.skillCode}` }));
  const toolOptions = tools.map((item) => ({ value: item.toolCode,
    label: `${item.name || item.toolCode} · ${item.toolCode}` }));
  const watchedModelCodes = Form.useWatch('selectableModelCodes', form);
  const selectableModels = useMemo(() => watchedModelCodes || [], [watchedModelCodes]);
  const defaultModel = Form.useWatch('defaultModelCode', form);
  const outputKind = Form.useWatch('outputKind', form) || 'TEXT';
  const executorType = Form.useWatch('executorType', form) || 'MODEL_PLAN';
  const delegatedSkillCode = Form.useWatch('delegatedSkillCode', form);
  const orchestratorSkills = useMemo(() => skills.filter((item) =>
    item.currentExecutorType === 'ORCHESTRATOR'), [skills]);
  const delegatedSkill = orchestratorSkills.find((item) => item.skillCode === delegatedSkillCode);
  const eligibleModels = useMemo(() => models.filter((item) => !item.modelType
    || item.modelType.toUpperCase() === outputKind || selectableModels.includes(item.modelCode)),
  [models, outputKind, selectableModels]);
  const selectedModels = useMemo(() => selectableModels.map((code: string) =>
    models.find((item) => item.modelCode === code)).filter(Boolean) as SkillTextModelOption[],
  [models, selectableModels]);

  const columns: ColumnsType<RuntimeAgentSummary> = [
    { title: 'Agent', dataIndex: 'name', render: (_, row) => <Space direction="vertical" size={0}>
      <Typography.Text strong>{row.name}</Typography.Text>
      <Typography.Text copyable type="secondary">{row.agentCode}</Typography.Text>
    </Space> },
    { title: '介绍', dataIndex: 'description', ellipsis: true, render: (value) => value || '--' },
    { title: '可见范围', dataIndex: 'visibility', width: 100,
      render: (value) => <Tag>{value === 'PUBLIC' ? '公开' : '私有'}</Tag> },
    { title: '当前版本', dataIndex: 'currentVersionId', width: 100,
      render: (value) => value || '未发布' },
    { title: '状态', dataIndex: 'status', width: 90,
      render: (_, row) => <Tag color={row.status === '0' && row.delFlag !== '1' ? 'success' : undefined}>
        {row.delFlag === '1' ? '已删除' : row.status === '0' ? '启用' : '停用'}</Tag> },
    { title: '更新时间', dataIndex: 'updateTime', width: 160, render: (value) => value || '--' },
    { title: '操作', width: 280, fixed: 'right', render: (_, row) => <Space size={0}>
      {canQuery && <><Button type="link" icon={<HistoryOutlined />}
        onClick={() => void openDetail(row)}>配置与版本</Button>
        <Button type="link" icon={<EyeOutlined />} onClick={() => openIdentity(row)}>信息</Button></>}
      {canEdit && row.delFlag !== '1' && <Switch aria-label={`${row.name}启用状态`}
        checked={row.status === '0'} onChange={(checked) => void changeStatus(row, checked ? '0' : '1')} />}
      {hasPermi('aid:runtimeAgent:remove') && row.delFlag !== '1' && <Popconfirm
        title="删除 Agent？" description="有效引用存在时服务端会拒绝。"
        onConfirm={() => remove(row)}><Button danger type="link" icon={<DeleteOutlined />}>删除</Button></Popconfirm>}
    </Space> }
  ];

  if (!canList) return <Alert type="warning" showIcon message="没有 Agent 查询权限" />;
  return <div>
    <PageHeader title="Agent 管理" desc="管理可复用 Agent 的草稿、已发布版本及运行记录。" />
    <Card bordered={false}>
      <Space wrap style={{ marginBottom: 16 }}>
        {canEdit && <Button type="primary" icon={<PlusOutlined />}
          onClick={openCreate}>新建 Agent</Button>}
        <Input allowClear prefix={<SearchOutlined />} placeholder="名称或编码" style={{ width: 220 }}
          onPressEnter={(event) => { setPage(1); setQuery({ ...query, keyword: event.currentTarget.value.trim() }); }} />
        <Select allowClear placeholder="状态" style={{ width: 120 }}
          options={[{ value: '0', label: '启用' }, { value: '1', label: '停用' }]}
          onChange={(status) => { setPage(1); setQuery({ ...query, status }); }} />
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(true)}>刷新</Button>
      </Space>
      {error && <Alert showIcon type="error" message={error} style={{ marginBottom: 16 }} />}
      <Table rowKey="id" dataSource={records} columns={columns} loading={loading}
        scroll={{ x: 1050 }} locale={{ emptyText: <Empty description="暂无 Agent" /> }}
        pagination={{ current: page, pageSize: size, total, showSizeChanger: true,
          onChange: (nextPage, nextSize) => { setPage(nextPage); setSize(nextSize); } }} />
    </Card>

    <Modal open={createOpen} title="新建 Agent · 基本信息" width={620} destroyOnClose
      onCancel={() => setCreateOpen(false)} onOk={create} okText="创建并配置" confirmLoading={creating}>
      <Form form={createForm} layout="vertical">
        <Form.Item label="创建方式"><Radio.Group value={createMode} optionType="button" buttonStyle="solid"
          options={[{ value: 'blank', label: '空白新建' }, { value: 'template', label: '从已发布版本复制' }]}
          onChange={(event) => { setCreateMode(event.target.value);
            createForm.setFieldValue('templateVersionId', undefined);
            if (event.target.value === 'template') void searchTemplates(); }} /></Form.Item>
        {createMode === 'template' && <Form.Item name="templateVersionId" label="模板版本"
          rules={[{ required: true, message: '请选择模板' }]}>
          <Select showSearch filterOption={false} loading={templateLoading}
            placeholder="输入名称或编码搜索已发布 Agent" options={templateOptions}
            onSearch={(value) => { if (templateTimer.current) clearTimeout(templateTimer.current);
              templateTimer.current = setTimeout(() => void searchTemplates(value.trim()), 250); }} />
        </Form.Item>}
        <Form.Item name="agentCode" label="稳定编码" rules={[{ required: true },
          { pattern: /^[a-z][a-z0-9._-]{1,63}$/, message: '以小写字母开头，2–64 位' }]}>
          <Input maxLength={64} placeholder="例如 story_writer" /></Form.Item>
        <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="description" label="介绍"><Input.TextArea rows={3} maxLength={1000} showCount /></Form.Item>
        {canEditGroups && <Form.Item name="groupIds" label="业务分组" extra="可加入多个业务分组，与模型功能池独立。">
          <Select mode="multiple" showSearch optionFilterProp="label"
            options={createGroups.map((item) => ({ value: item.id,
              label: `${item.name} · ${item.groupCode}` }))} /></Form.Item>}
        <Form.Item name="visibility" label="可见范围"><Select options={[
          { value: 'PRIVATE', label: '私有' }, { value: 'PUBLIC', label: '公开' }
        ]} /></Form.Item>
      </Form>
    </Modal>

    <Drawer open={!!identityOpen} width="min(640px, 100vw)" title="Agent 信息"
      onClose={() => setIdentityOpen(undefined)} extra={canEdit && <Button type="primary"
        loading={identitySaving} onClick={saveIdentity}>保存</Button>}>
      {identityOpen && <Descriptions size="small" bordered column={1} items={[
        { key: 'code', label: '稳定编码', children: identityOpen.agentCode },
        { key: 'version', label: '当前版本 ID', children: identityOpen.currentVersionId || '未发布' }
      ]} />}
      <Form form={identityForm} layout="vertical" disabled={!canEdit} style={{ marginTop: 16 }}>
        <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input maxLength={100} /></Form.Item>
        <Form.Item name="description" label="介绍"><Input.TextArea maxLength={1000} rows={4} showCount /></Form.Item>
        <Form.Item name="iconUrl" label="图标 URL"><Input maxLength={500} /></Form.Item>
        <Form.Item name="visibility" label="可见范围"><Select options={[
          { value: 'PRIVATE', label: '私有' }, { value: 'PUBLIC', label: '公开' }
        ]} /></Form.Item>
      </Form>
    </Drawer>

    <Drawer open={!!selected} width="min(1180px, 100vw)" title={selected ? `${selected.name} · 配置与版本` : ''}
      onClose={() => { if (dirty) Modal.confirm({ title: '放弃未保存修改？',
        onOk: () => setSelected(undefined), okText: '放弃', okButtonProps: { danger: true } });
      else setSelected(undefined); }} extra={<Button icon={<ReloadOutlined />} loading={draftLoading}
        onClick={reloadDetail}>刷新</Button>}>
      {draftError && <Alert type="error" showIcon message={draftError} style={{ marginBottom: 16 }} />}
      <Space wrap style={{ marginBottom: 16 }}>
        <Button type={activeTab === 'draft' ? 'primary' : 'default'} onClick={() => setActiveTab('draft')}>编辑草稿</Button>
        <Button type={activeTab === 'versions' ? 'primary' : 'default'} onClick={() => setActiveTab('versions')}>版本（{versionTotal}）</Button>
        {hasPermi('aid:runtimeAgent:run:list') && <Button type={activeTab === 'runs' ? 'primary' : 'default'}
          onClick={() => { setActiveTab('runs'); if (selected) void loadRuns(selected.id); }}>运行记录</Button>}
      </Space>
      {activeTab === 'draft' && (draftLoading ? <Card loading /> : draft ? <>
        <Alert type="info" showIcon style={{ marginBottom: 16 }} message="草稿不会改变当前执行版本"
          description="保存并校验后发布；发布成功仍需在版本页显式切换。" />
        <Form form={form} layout="vertical" disabled={!canEdit || saving || validating || publishing}
          onValuesChange={() => { setDirty(true); setValidation(undefined); }}>
          <Form.Item name="executorType" label="执行方式" rules={[{ required: true }]}>
            <Radio.Group optionType="button" buttonStyle="solid" options={[
              { value: 'MODEL_PLAN', label: '模型与步骤' },
              { value: 'SKILL_ORCHESTRATOR', label: '委托编排 Skill' }
            ]} onChange={(event) => {
              if (event.target.value === 'SKILL_ORCHESTRATOR') {
                form.setFieldsValue({ outputKind: 'TEXT', delegatedSkillCode: undefined,
                  allowedSkillCodes: [], allowedToolCodes: [], steps: [] });
              } else form.setFieldValue('delegatedSkillCode', undefined);
            }} />
          </Form.Item>
          {executorType === 'SKILL_ORCHESTRATOR' && <>
            <Form.Item name="delegatedSkillCode" label="编排 Skill" rules={[{ required: true,
              message: '请选择已发布的编排 Skill' }]} extra="执行时固定使用该 Skill 的当前已发布编排版本；通用步骤、Tool 和 Skill 白名单不参与委托执行。">
              <Select showSearch optionFilterProp="label" placeholder="选择已发布的编排 Skill"
                notFoundContent="暂无可用编排 Skill"
                options={orchestratorSkills.map((item) => ({ value: item.skillCode,
                  label: `${item.name} · ${item.skillCode}` }))}
                onChange={(code: string) => {
                  const match = orchestratorSkills.find((item) => item.skillCode === code);
                  const model = models.find((item) => item.modelCode === match?.currentModelCode);
                  if (model?.available && (!model.modelType || model.modelType.toUpperCase() === 'TEXT')) {
                    const selectedCodes = form.getFieldValue('selectableModelCodes') || [];
                    form.setFieldsValue({ selectableModelCodes: [...new Set([...selectedCodes, model.modelCode])],
                      defaultModelCode: model.modelCode });
                  }
                }} />
            </Form.Item>
            {delegatedSkill?.currentModelCode && <Alert type="info" showIcon style={{ marginBottom: 16 }}
              message={`编排 Skill 当前模型：${delegatedSkill.currentModelCode}`}
              description="默认模型可选与该 Skill 相同的已启用文本模型；实际委托调用由固定 Skill 版本执行。" />}
          </>}
          <Form.Item name="outputKind" label="输出类型" rules={[{ required: true }]}>
            <Select disabled={executorType === 'SKILL_ORCHESTRATOR'} options={[{ value: 'TEXT', label: '文本' }, { value: 'IMAGE', label: '图片' },
              { value: 'VIDEO', label: '视频' }]} onChange={() => {
              form.setFieldsValue({ selectableModelCodes: [], defaultModelCode: undefined });
            }} />
          </Form.Item>
          <Form.Item name="instruction" label="Agent 指令" rules={[{ required: true, message: '请输入指令' }]}>
            <Input.TextArea autoSize={{ minRows: 8, maxRows: 20 }} maxLength={100000} showCount />
          </Form.Item>
          <Form.Item name="selectableModelCodes" label="可选模型" rules={[{ required: true, message: '请选择模型' }]}>
            <Select mode="multiple" showSearch optionFilterProp="label" maxCount={20}
              options={eligibleModels.map((item) => ({ value: item.modelCode,
                label: `${item.modelName || item.modelCode} · ${item.modelCode}`,
                disabled: (!item.available && !selectableModels.includes(item.modelCode))
                  || (!!item.modelType && item.modelType.toUpperCase() !== outputKind) }))}
              onChange={(codes: string[]) => { if (!codes.includes(form.getFieldValue('defaultModelCode')))
                form.setFieldValue('defaultModelCode', codes[0]); }} />
          </Form.Item>
          <Form.Item name="defaultModelCode" label="默认模型" rules={[{ required: true, message: '请选择默认模型' }]}>
            <Select showSearch optionFilterProp="label"
              options={selectedModels.map((item) => ({ value: item.modelCode,
                label: item.modelName || item.modelCode,
                disabled: !item.available || (!!item.modelType && item.modelType.toUpperCase() !== outputKind) }))} />
          </Form.Item>
          {selectedModels.length > 0 && <Space wrap style={{ marginBottom: 16 }}>
            {selectedModels.map((item) => <Card size="small" key={item.modelCode}
              title={modelLabel(item)} style={{ minWidth: 220 }}>
              {item.modelCode === defaultModel && <Tag color="blue">默认</Tag>}
              {!item.available && <Tag color="error">不可用</Tag>}
            </Card>)}
          </Space>}
          <Button style={{ display: 'block', marginBottom: 18 }} onClick={() => setAdvancedOpen((current) => !current)}>
            {advancedOpen ? '收起高级配置' : executorType === 'SKILL_ORCHESTRATOR'
              ? '展开高级配置 · 运行限额' : '展开高级配置 · Skill、Tool、步骤与运行限额'}
          </Button>
          <div style={{ display: advancedOpen ? 'block' : 'none' }}>
            {executorType === 'MODEL_PLAN' && <>
            <Divider orientation="left">允许使用的能力</Divider>
            <Form.Item name="allowedSkillCodes" label="Skill 白名单">
              <Select mode="multiple" showSearch optionFilterProp="label" maxCount={16} options={skillOptions} />
            </Form.Item>
            <Form.Item name="allowedToolCodes" label="已注册 Tool 白名单">
              <Select mode="multiple" showSearch optionFilterProp="label" maxCount={16} options={toolOptions} />
            </Form.Item>
            <Divider orientation="left">执行步骤</Divider>
            <Form.List name="steps">{(fields, { add, remove, move }) => <div>
              {fields.map((field, index) => <StepFields key={field.key} name={field.name}
                form={form} skillOptions={skillOptions} toolOptions={toolOptions}
                index={index} count={fields.length} remove={() => remove(field.name)}
                move={(to) => move(index, to)} />)}
              <Button type="dashed" block icon={<PlusOutlined />} disabled={fields.length >= 8}
                onClick={() => add({ stepKey: `step_${fields.length + 1}`, kind: 'MODEL' })}>添加步骤</Button>
            </div>}</Form.List>
            </>}
            <Divider orientation="left">运行限额</Divider>
            <Space wrap align="start">
              <Form.Item name="maxSteps" label="最多步骤" rules={[{ required: true }]}>
                <InputNumber min={1} max={8} precision={0} /></Form.Item>
              <Form.Item name="maxTaskCount" label="最多任务" rules={[{ required: true }]}>
                <InputNumber min={1} max={12} precision={0} /></Form.Item>
              <Form.Item name="maxDurationSeconds" label="最长时间（秒）" rules={[{ required: true }]}>
                <InputNumber min={10} max={3600} precision={0} /></Form.Item>
              <Form.Item name="maxEstimatedTokens" label="估算 Token 上限" rules={[{ required: true }]}>
                <InputNumber min={256} max={2000000} precision={0} /></Form.Item>
              <Form.Item name="clarificationEnabled" label="允许澄清" valuePropName="checked">
                <Switch /></Form.Item>
            </Space>
          </div>
        </Form>
        {validation && <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>
          <Alert type={validation.valid ? 'success' : 'error'} showIcon
            message={validation.valid ? '草稿校验通过' : '草稿校验失败'} />
          {[...(validation.errors || []), ...(validation.warnings || [])].map((issue, index) =>
            <Alert key={`${issue.field}-${index}`} type="warning" showIcon
              message={`${issue.field || '配置'}：${issue.message}`} />)}
        </Space>}
        {canEdit && <Space wrap style={{ marginTop: 20 }}>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={saveDraft}>保存草稿</Button>
          <Button icon={<CheckCircleOutlined />} loading={validating} onClick={validateDraft}>校验</Button>
          <Button disabled={!canPublish} onClick={() => setTryRunOpen(true)}>样例试运行</Button>
          <Button icon={<RocketOutlined />} disabled={!canPublish} onClick={() => setPublishOpen(true)}>发布版本</Button>
          {!canPublish && <Typography.Text type="secondary">先保存并校验当前草稿</Typography.Text>}
        </Space>}
      </> : <Empty description="暂无草稿" />)}
      {activeTab === 'versions' && <Table rowKey="id" dataSource={versions} scroll={{ x: 760 }}
        pagination={{ current: versionPage, pageSize: versionSize, total: versionTotal,
          showSizeChanger: true, onChange: (p, s) => selected && loadVersions(selected.id, p, s) }}
        columns={[
          { title: '版本', dataIndex: 'versionCode', render: (value: string, row: RuntimeAgentVersion) =>
            <Space>{value}{String(row.id) === String(selected?.currentVersionId)
              && <Tag color="success">当前</Tag>}</Space> },
          { title: '发布人', dataIndex: 'createBy', render: (value: string) => value || '--' },
          { title: '发布时间', dataIndex: 'createTime' },
          { title: '操作', width: 210, render: (_: unknown, row: RuntimeAgentVersion) => <Space>
            <Button type="link" onClick={async () => {
              try { const response = await getRuntimeAgentVersion(row.id); setVersionDetail(response.data); }
              catch (cause: any) { message.error(cause?.message || '版本加载失败'); }
            }}>详情</Button>
            {canEdit && String(row.id) !== String(selected?.currentVersionId) && <Popconfirm
              title={`切换至 ${row.versionCode}？`} description="仅影响后续运行。"
              onConfirm={() => activate(row)}><Button type="link">切换</Button></Popconfirm>}
          </Space> }
        ]} locale={{ emptyText: <Empty description="暂无已发布版本" /> }} />}
      {activeTab === 'runs' && <Table rowKey="id" dataSource={runs} loading={runLoading}
        scroll={{ x: 900 }} pagination={{ current: runPage, pageSize: runSize, total: runTotal,
          showSizeChanger: true, onChange: (p, s) => selected && loadRuns(selected.id, p, s) }}
        columns={[
          { title: 'Run ID', dataIndex: 'id', width: 100 },
          { title: '用户 ID', dataIndex: 'userId', width: 100 },
          { title: '版本 ID', dataIndex: 'versionId', width: 100 },
          { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Tag>{value}</Tag> },
          { title: '阶段', dataIndex: 'stage', render: (value: string) => value || '--' },
          { title: '媒体任务', dataIndex: 'mediaTaskId', render: (value: RuntimeId) => value || '--' },
          { title: '开始时间', dataIndex: 'createTime' },
          { title: '操作', width: 90, render: (_: unknown, row: RuntimeAgentRun) =>
            hasPermi('aid:runtimeAgent:run:query') && <Button type="link"
              onClick={() => showRun(row.id)}>详情</Button> }
        ]} locale={{ emptyText: <Empty description="暂无运行记录" /> }} />}
    </Drawer>
    <TryRunModal open={tryRunOpen} memberType="AGENT" memberId={selected?.id} draftId={draft?.draftId}
      draftDigest={draft?.draftDigest} defaultModelCode={draft?.defaultModelCode}
      allowedModelCodes={draft?.selectableModelCodes}
      outputKind={draft?.outputKind}
      onClose={() => setTryRunOpen(false)}
      onOpenRunRecords={openTryRunRecords} />

    <Modal open={publishOpen} title="发布不可变版本" onCancel={() => setPublishOpen(false)}
      onOk={publishDraft} confirmLoading={publishing} okText="发布（暂不切换）">
      <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="发布后仍需显式切换" />
      <Form form={publishForm} layout="vertical">
        <Form.Item name="versionCode" label="版本号" rules={[{ required: true },
          { pattern: SEMVER, message: '请输入 SemVer，例如 1.0.0' }]}>
          <Input placeholder="1.0.0" /></Form.Item>
      </Form>
    </Modal>
    <Drawer open={!!versionDetail} width="min(850px, 100vw)" title={versionDetail
      ? `版本 ${versionDetail.versionCode}` : '版本详情'} onClose={() => setVersionDetail(undefined)}>
      {versionDetail && <>
        <Descriptions bordered size="small" column={1} items={[
          { key: 'executor', label: '执行方式', children: versionDetail.executorType === 'SKILL_ORCHESTRATOR'
            ? '委托编排 Skill' : '模型与步骤' },
          ...(versionDetail.executorType === 'SKILL_ORCHESTRATOR' ? [
            { key: 'delegated', label: '编排 Skill', children: versionDetail.delegatedSkillCode || '--' }
          ] : []),
          { key: 'kind', label: '输出类型', children: versionDetail.outputKind || 'TEXT' },
          { key: 'model', label: '默认模型', children: versionDetail.defaultModelCode },
          { key: 'models', label: '可选模型', children: versionDetail.selectableModelCodes.join('、') },
          { key: 'skills', label: '可用 Skill', children: versionDetail.allowedSkillCodes.join('、') || '--' },
          { key: 'tools', label: '可用 Tool', children: versionDetail.allowedToolCodes.join('、') || '--' },
          { key: 'maxSteps', label: '最多步骤', children: versionDetail.maxSteps },
          { key: 'maxTaskCount', label: '最多任务', children: versionDetail.maxTaskCount },
          { key: 'maxDurationSeconds', label: '最长时间（秒）', children: versionDetail.maxDurationSeconds },
          { key: 'maxEstimatedTokens', label: '估算 Token 上限', children: versionDetail.maxEstimatedTokens },
          { key: 'clarificationEnabled', label: '允许澄清', children: versionDetail.clarificationEnabled ? '是' : '否' },
          { key: 'digest', label: '包摘要', children: <Typography.Text copyable>{versionDetail.packageDigest}</Typography.Text> }
        ]} />
        <Divider orientation="left">指令</Divider>
        <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{versionDetail.instruction}</Typography.Paragraph>
        <Divider orientation="left">执行步骤</Divider>
        <List dataSource={versionDetail.steps || []} renderItem={(step) => <List.Item>
          <Space><Tag>{step.kind}</Tag><Typography.Text strong>{step.stepKey}</Typography.Text>
            <span>{step.toolCode || step.skillCode || step.instruction || '--'}</span></Space>
        </List.Item>} />
      </>}
    </Drawer>
    <Drawer open={!!runDetail} width="min(880px, 100vw)" title={runDetail ? `Run #${runDetail.id}` : '运行详情'}
      onClose={() => setRunDetail(undefined)}>
      {runDetail && <>
        <Descriptions bordered size="small" column={1} items={[
          { key: 'status', label: '状态', children: runDetail.status },
          { key: 'version', label: '版本 ID', children: runDetail.versionId || runDetail.agentVersionId },
          { key: 'cost', label: '运行实际费用', children: runDetail.actualCost ?? '--' },
          { key: 'billing', label: '运行计费状态', children: billingStatusTag(runDetail.billingStatus) },
          { key: 'error', label: '错误', children: runDetail.errorMessage || '--' },
          { key: 'errorReason', label: '错误原因', children: runDetail.errorDetails?.reason || '--' },
          { key: 'allowedValues', label: '可选值', children: runDetail.errorDetails?.allowedValues?.join('、') || '--' }
        ]} />
        <RuntimeRunMedia run={runDetail} />
        <Divider orientation="left">步骤与计费</Divider>
        <Table size="small" rowKey={(row, index) => `${row.stepKey}-${index}`}
          dataSource={runDetail.steps || []} scroll={{ x: 900 }} pagination={false}
          columns={[
            { title: '步骤', dataIndex: 'stepKey' }, { title: '状态', dataIndex: 'status' },
            { title: '方式', dataIndex: 'stepKind' },
            { title: '模型/Tool/Skill', render: (_: unknown, row: NonNullable<RuntimeAgentRun['steps']>[number]) =>
              row.modelCode || row.toolCode || row.skillCode || row.skillVersionId || '--' },
            { title: '媒体任务', dataIndex: 'mediaTaskId', render: (value: RuntimeId) => value || '--' },
            { title: '计费状态', dataIndex: 'billingStatus',
              render: (value?: string) => billingStatusTag(value) },
            { title: '实际费用', dataIndex: 'actualCost',
              render: (value?: string | number) => value ?? '--' },
            { title: '错误', dataIndex: 'errorMessage' }
          ]} />
        <Divider orientation="left">输入</Divider>
        <StructuredValueEditor disabled value={serialized(runDetail.inputPreview ?? runDetail.inputJson)} />
        <Divider orientation="left">输出</Divider>
        <StructuredValueEditor disabled value={serialized(runDetail.resultPreview ?? runDetail.outputJson)} />
      </>}
    </Drawer>
    <Drawer open={delegatedRunId != null} width="min(900px, 100vw)"
      title={delegatedRunId == null ? '委托 Skill Run' : `委托 Skill Run #${String(delegatedRunId)}`}
      onClose={closeDelegatedRun} loading={delegatedRunLoading}
      extra={delegatedRunId != null && <Button icon={<ReloadOutlined />} disabled={delegatedRunLoading}
        onClick={() => void showDelegatedRun(delegatedRunId)}>刷新</Button>}>
      {delegatedRunError && <Alert type="error" showIcon message="委托运行详情读取失败"
        description={delegatedRunError} style={{ marginBottom: 16 }} />}
      {delegatedRunDetail && <>
        <Alert type="info" showIcon style={{ marginBottom: 16 }} message="这是委托 Skill Run"
          description="记录来自 Skill 编排运行审计，不属于通用 Runtime Agent Run。" />
        <Descriptions bordered size="small" column={1} items={[
          { key: 'id', label: 'Legacy Run ID', children:
            <Typography.Text copyable>{delegatedRunDetail.id}</Typography.Text> },
          { key: 'status', label: '状态', children: <Tag>{delegatedRunDetail.status}</Tag> },
          { key: 'stage', label: '阶段', children: delegatedRunDetail.stage || '--' },
          { key: 'skill', label: 'Skill / 版本', children:
            `${delegatedRunDetail.skillId} / ${delegatedRunDetail.skillVersionId}` },
          { key: 'model', label: '模型', children: delegatedRunDetail.modelCode || '--' },
          { key: 'scope', label: '项目 / 剧集', children: delegatedRunDetail.projectId == null
            ? '未关联项目' : `${delegatedRunDetail.projectId} / ${delegatedRunDetail.episodeId ?? '—'}` },
          { key: 'error', label: '错误', children: delegatedRunDetail.errorMessage || '--' }
        ]} />
        <Divider orientation="left">步骤与计费任务</Divider>
        <Table size="small" pagination={false} rowKey="stepId" dataSource={delegatedRunDetail.tasks || []}
          scroll={{ x: 760 }} columns={[
            { title: '步骤', dataIndex: 'stepKey', width: 120 },
            { title: '编排状态', dataIndex: 'orchestrationStatus', width: 130 },
            { title: '媒体任务', dataIndex: 'mediaTaskId', width: 130,
              render: (value: RuntimeId | undefined) => value ?? '--' },
            { title: '任务状态', dataIndex: 'mediaStatus', width: 110,
              render: (value: string | undefined) => value || '--' },
            { title: '计费状态', dataIndex: 'billingStatus', width: 110,
              render: (value: string | undefined) => value || '--' },
            { title: '实际费用', dataIndex: 'actualCost', width: 100,
              render: (value: string | undefined) => value ?? '--' }
          ]} />
        <Divider orientation="left">输入</Divider>
        <StructuredValueEditor disabled value={serialized(delegatedRunDetail.inputJson)} />
        <Divider orientation="left">输出</Divider>
        <StructuredValueEditor disabled value={serialized(delegatedRunDetail.outputJson)} />
      </>}
    </Drawer>
  </div>;
}
