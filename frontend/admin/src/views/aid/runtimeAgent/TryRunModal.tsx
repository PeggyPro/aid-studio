import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Form, Input, InputNumber, Modal, Select, Space, Tag, Typography, message } from 'antd';
import { request, wasRequestErrorReported } from '@/utils/request';
import { listSkillTextModels, type SkillTextModelOption } from '@/api/aid/skill';
import { listRuntimeAgentModelOptions, type GenericRuntimeTryRunReceipt,
  type RuntimeTryRunReceipt, type RuntimeTryRunRecordTarget } from '@/api/aid/legacyRuntimePlanAgent';
import StructuredValueEditor from '@/views/aid/skill/StructuredValueEditor';
import { useUserStore } from '@/store/useUserStore';
import RuntimeRunMedia from './RuntimeRunMedia';

const TRY_RUN_TIMEOUT_MS = 360_000;

interface Props {
  open: boolean;
  memberType: 'SKILL' | 'AGENT';
  memberId?: number | string;
  draftId?: number | string;
  draftDigest?: string;
  defaultModelCode?: string;
  allowedModelCodes?: string[];
  outputKind?: 'TEXT' | 'IMAGE' | 'VIDEO';
  onClose: () => void;
  onOpenRunRecords?: (target: RuntimeTryRunRecordTarget) => void;
}

interface Values {
  grantId: string;
  prompt: string;
  modelCode: string;
  capabilityCode?: string;
  projectId?: string;
  size?: string;
  aspectRatio?: string;
  expectedImageCount?: number;
  durationSeconds?: number;
}

interface TryRunRequestData {
  draftId: number | string;
  draftDigest: string;
  grantId: string;
  clientRequestId: string;
  modelCode: string;
  capabilityCode?: string;
  prompt: string;
  projectId?: string;
  size?: string;
  aspectRatio?: string;
  expectedImageCount?: number;
  durationSeconds?: number;
}

interface PendingSubmission {
  sessionKey: string;
  signature: string;
  values: Values;
  data: TryRunRequestData;
}

type SubmissionState = 'idle' | 'uncertain' | 'recovery' | 'completed';

function hasId(value: unknown) {
  return (typeof value === 'string' && /^[1-9][0-9]*$/.test(value))
    || (typeof value === 'number' && Number.isSafeInteger(value) && value > 0);
}

function invalidReceipt(messageText: string, uncertain: boolean) {
  const error = new Error(messageText) as Error & { submissionUncertain?: boolean };
  error.submissionUncertain = uncertain;
  return error;
}

function parseReceipt(value: unknown, pending: PendingSubmission): RuntimeTryRunReceipt {
  if (!value || typeof value !== 'object') throw invalidReceipt('服务端未返回有效的试运行回执', true);
  const receipt = value as Record<string, unknown>;
  if (receipt.accepted !== true) {
    throw invalidReceipt('服务端回执缺少有效的受理确认，需要恢复核实本次提交', true);
  }
  if (receipt.requestClientRequestId !== pending.data.clientRequestId) {
    throw invalidReceipt('服务端回执与当前请求 ID 不匹配，不能关联到本次试运行', true);
  }
  if (typeof receipt.recoveryRequired !== 'boolean') {
    throw invalidReceipt('服务端回执缺少恢复状态', true);
  }
  if (receipt.runType === 'GENERIC_RUNTIME') {
    if (!hasId(receipt.id)) throw invalidReceipt('通用 Runtime 回执缺少 Run ID', true);
    if (receipt.recoveryRequired) {
      if (typeof receipt.clientRequestId !== 'string' || !receipt.clientRequestId.trim()) {
        throw invalidReceipt('通用 Runtime 有限回执缺少持久化请求标识', true);
      }
    } else if (typeof receipt.status !== 'string' || !receipt.status.trim()) {
      throw invalidReceipt('通用 Runtime 完整回执缺少运行状态', true);
    }
    return value as RuntimeTryRunReceipt;
  }
  if (receipt.runType === 'SKILL_ORCHESTRATOR') {
    if (!hasId(receipt.legacyRunId) || !hasId(receipt.runId)
      || String(receipt.legacyRunId) !== String(receipt.runId)) {
      throw invalidReceipt('委托 Skill 回执缺少一致的 Legacy Run ID', true);
    }
    if (!receipt.recoveryRequired && (typeof receipt.status !== 'string' || !receipt.status.trim())) {
      throw invalidReceipt('委托 Skill 完整回执缺少运行状态', true);
    }
    return value as RuntimeTryRunReceipt;
  }
  throw invalidReceipt('服务端返回了未知的试运行类型', true);
}

function isCompleteGeneric(receipt: RuntimeTryRunReceipt): receipt is Extract<GenericRuntimeTryRunReceipt,
  { recoveryRequired: false }> {
  return receipt.runType === 'GENERIC_RUNTIME' && !receipt.recoveryRequired;
}

function newClientRequestId() {
  return crypto.randomUUID?.()
    || `admin-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function isAmbiguousSubmissionError(cause: any) {
  const code = String(cause?.code || '').toUpperCase();
  const text = String(cause?.message || '').toLowerCase();
  const status = Number(cause?.response?.status || 0);
  return code === 'ECONNABORTED' || code === 'ETIMEDOUT' || code === 'ERR_NETWORK'
    || text === 'network error' || text.includes('timeout')
    || status >= 500;
}

/** 使用测试用户签发的一次性授权，对已保存草稿发起真实计费试运行。 */
export default function TryRunModal({ open, memberType, memberId, draftId, draftDigest, defaultModelCode,
  allowedModelCodes, outputKind = 'TEXT', onClose, onOpenRunRecords }: Props) {
  const adminUserId = useUserStore((state) => state.id);
  const [form] = Form.useForm<Values>();
  const [models, setModels] = useState<SkillTextModelOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [receipt, setReceipt] = useState<RuntimeTryRunReceipt>();
  const [submissionState, setSubmissionState] = useState<SubmissionState>('idle');
  const [submissionError, setSubmissionError] = useState('');
  const active = useRef(false);
  const modelSequence = useRef(0);
  const submissionStateRef = useRef<SubmissionState>('idle');
  const pendingSubmission = useRef<PendingSubmission>();
  const modelCode = Form.useWatch('modelCode', form);
  const selectedModel = models.find((item) => item.modelCode === modelCode);
  const capabilities = (selectedModel?.capabilities || []).filter((item) => item.enabled !== false);
  const sessionKey = `${memberType}:${String(memberId ?? '')}:${String(draftId ?? '')}:${draftDigest || ''}`;

  const changeSubmissionState = useCallback((state: SubmissionState) => {
    submissionStateRef.current = state;
    setSubmissionState(state);
  }, []);

  useEffect(() => {
    const sequence = ++modelSequence.current;
    if (!open) {
      setLoading(false);
      return;
    }
    const pending = pendingSubmission.current;
    const preserveUncertain = (submissionStateRef.current === 'uncertain'
      || submissionStateRef.current === 'recovery')
      && pending?.sessionKey === sessionKey;
    if (preserveUncertain && pending) {
      form.setFieldsValue(pending.values);
    } else {
      setModels([]);
      setReceipt(undefined);
      setSubmissionError('');
      changeSubmissionState('idle');
      pendingSubmission.current = undefined;
      form.resetFields();
      form.setFieldsValue({ modelCode: defaultModelCode, prompt: '', projectId: undefined,
        expectedImageCount: outputKind === 'IMAGE' ? 1 : undefined });
    }
    setLoading(true);
    void (memberType === 'SKILL' ? listSkillTextModels() : listRuntimeAgentModelOptions())
      .then((response) => {
        if (sequence === modelSequence.current) setModels(response.data || []);
      }, () => {
        if (sequence === modelSequence.current) setModels([]);
      })
      .finally(() => {
        if (sequence === modelSequence.current) setLoading(false);
      });
  }, [changeSubmissionState, defaultModelCode, form, memberType, open, outputKind, sessionKey]);

  const requestSubmission = async (pending: PendingSubmission) => {
    if (active.current) return;
    active.current = true;
    setSubmitting(true);
    setSubmissionError('');
    try {
      const response = await request<RuntimeTryRunReceipt>({
        url: memberType === 'SKILL' ? '/aid/skill/draft/try-run' : '/aid/runtime-plan-agent/draft/try-run',
        method: 'post',
        timeout: TRY_RUN_TIMEOUT_MS,
        headers: memberType === 'AGENT' ? { 'X-Aid-Runtime-Agent-Family': 'LEGACY_PLAN' } : undefined,
        data: pending.data
      });
      const nextReceipt = parseReceipt(response.data, pending);
      setReceipt(nextReceipt);
      if (nextReceipt.recoveryRequired) {
        setSubmissionError('服务端已受理运行，但完整状态投影尚未返回。请使用原请求显式恢复运行详情。');
        changeSubmissionState('recovery');
        return;
      }
      changeSubmissionState('completed');
      const runId = nextReceipt.runType === 'GENERIC_RUNTIME'
        ? nextReceipt.id : nextReceipt.legacyRunId;
      message.success(`试运行已提交，Run ID：${String(runId)}`);
    } catch (cause: any) {
      if (cause?.errorFields) return;
      const acceptedReceipt = submissionStateRef.current === 'recovery'
        && receipt?.recoveryRequired === true;
      const restoringUncertain = submissionStateRef.current === 'uncertain';
      const uncertain = restoringUncertain || cause?.submissionUncertain || isAmbiguousSubmissionError(cause);
      const text = acceptedReceipt
        ? `运行已受理；本次详情恢复失败：${cause?.message || '请求失败'}。请稍后继续使用原请求恢复。`
        : restoringUncertain
        ? `本次恢复未能确认原提交结果：${cause?.message || '请求失败'}。原请求仍需核实，请继续恢复或查看运行记录。`
        : uncertain
        ? '请求返回前连接中断或超时，服务端可能已经创建运行。请使用原请求显式恢复提交结果。'
        : cause?.message || '试运行失败';
      if (!acceptedReceipt) setReceipt(undefined);
      setSubmissionError(text);
      changeSubmissionState(acceptedReceipt ? 'recovery' : uncertain ? 'uncertain' : 'idle');
      if (!wasRequestErrorReported(cause)) message.error(cause?.message || '试运行失败');
    } finally {
      active.current = false;
      setSubmitting(false);
    }
  };

  const submit = async () => {
    if (active.current || !draftId || !draftDigest) return;
    try {
      const values = await form.validateFields();
      if (active.current) return;
      setReceipt(undefined);
      const signature = JSON.stringify({ sessionKey, values });
      const previous = pendingSubmission.current;
      const clientRequestId = previous?.signature === signature
        ? previous.data.clientRequestId : newClientRequestId();
      const pending: PendingSubmission = { sessionKey, signature, values, data: {
        draftId, draftDigest, grantId: values.grantId, clientRequestId,
        modelCode: values.modelCode, capabilityCode: values.capabilityCode,
        prompt: values.prompt,
        projectId: values.projectId, size: values.size || undefined,
        aspectRatio: values.aspectRatio || undefined,
        expectedImageCount: outputKind === 'IMAGE' ? values.expectedImageCount : undefined,
        durationSeconds: outputKind === 'VIDEO' ? values.durationSeconds : undefined
      } };
      pendingSubmission.current = pending;
      await requestSubmission(pending);
    } catch (cause: any) {
      if (!cause?.errorFields && !wasRequestErrorReported(cause)) message.error(cause?.message || '试运行失败');
    }
  };

  const recover = () => {
    const pending = pendingSubmission.current;
    if (!pending || pending.sessionKey !== sessionKey) {
      setSubmissionError('原提交上下文已失效，请关闭后重新打开当前草稿');
      return;
    }
    void requestSubmission(pending);
  };

  const openRunRecords = () => {
    if (!receipt || !onOpenRunRecords) return;
    const target: RuntimeTryRunRecordTarget = receipt.runType === 'GENERIC_RUNTIME'
      ? { kind: 'GENERIC_RUNTIME', memberType, runId: receipt.id }
      : { kind: 'SKILL_ORCHESTRATOR', runId: receipt.legacyRunId };
    onOpenRunRecords(target);
  };

  const completeGeneric = receipt && isCompleteGeneric(receipt) ? receipt : undefined;
  const taskId = completeGeneric?.mediaTaskId || completeGeneric?.task?.taskId;
  const runId = receipt?.runType === 'GENERIC_RUNTIME' ? receipt.id : receipt?.legacyRunId;
  const formLocked = submitting || submissionState !== 'idle';

  return <Modal open={open} width={690} title="已保存草稿试运行" maskClosable={false}
    onCancel={() => { if (!submitting) onClose(); }}
    footer={<Space wrap>
      <Button disabled={submitting} onClick={onClose}>关闭</Button>
      {receipt && onOpenRunRecords && <Button disabled={submitting} onClick={openRunRecords}>打开运行记录</Button>}
      <Button type="primary" loading={submitting} disabled={submissionState === 'completed'}
        onClick={submissionState === 'uncertain' || submissionState === 'recovery' ? recover : submit}>
        {submissionState === 'recovery' ? '恢复运行详情'
          : submissionState === 'uncertain' ? '恢复提交结果' : '提交真实运行'}
      </Button>
    </Space>}>
    <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="本次会真实调用模型并从授权测试用户账户计费"
      description="请由测试 C 用户先签发针对本草稿摘要的一次性授权，再填写授权 ID。失败按服务端原有账本流水处理。" />
    {submitting && <Alert type="info" showIcon style={{ marginBottom: 16 }}
      message={submissionState === 'uncertain' || submissionState === 'recovery'
        ? '正在恢复原提交结果' : '正在提交真实运行'}
      description="请保持弹窗打开。页面只发送当前这一次受控请求。" />}
    {submissionError && <Alert type={submissionState === 'uncertain' || submissionState === 'recovery'
      ? 'warning' : 'error'} showIcon style={{ marginBottom: 16 }}
      message={submissionState === 'recovery' ? '运行已受理，详情待恢复'
        : submissionState === 'uncertain' ? '提交结果待确认' : '试运行未提交'}
      description={<Space direction="vertical" size={4}>
        <Typography.Text>{submissionError}</Typography.Text>
        {(submissionState === 'uncertain' || submissionState === 'recovery')
          && pendingSubmission.current && <Typography.Text type="secondary">
          恢复只会重放相同请求体与请求 ID，不会创建新的请求标识。
        </Typography.Text>}
        {(submissionState === 'uncertain' || submissionState === 'recovery')
          && pendingSubmission.current && <Typography.Text code copyable>
          {pendingSubmission.current.data.clientRequestId}
        </Typography.Text>}
      </Space>} />}
    <Descriptions size="small" bordered column={{ xs: 1, sm: 2 }} style={{ marginBottom: 16 }} items={[
      { key: 'admin', label: '管理员 ID', children: <Typography.Text copyable>{adminUserId}</Typography.Text> },
      { key: 'type', label: '成员类型', children: memberType },
      { key: 'member', label: '成员 ID', children: <Typography.Text copyable>{memberId}</Typography.Text> },
      { key: 'digest', label: '草稿摘要', span: 2, children:
        <Typography.Text copyable style={{ overflowWrap: 'anywhere' }}>{draftDigest}</Typography.Text> }
    ]} />
    <Form form={form} layout="vertical" disabled={formLocked}>
      <Form.Item name="grantId" label="测试用户授权 ID" rules={[{ required: true, message: '请输入一次性授权 ID' },
        { pattern: /^[1-9][0-9]*$/, message: '授权 ID 必须为正整数数字字符串' }]}
        extra="请测试用户使用上方管理员、成员与草稿信息签发一次性授权，再填写授权编号。">
        <Input inputMode="numeric" autoComplete="off" maxLength={30} />
      </Form.Item>
      <Form.Item name="prompt" label="样例输入" rules={[{ required: true, message: '请输入样例' }]}>
        <Input.TextArea autoSize={{ minRows: 4, maxRows: 12 }} maxLength={100000} showCount />
      </Form.Item>
      <Form.Item name="modelCode" label="模型" rules={[{ required: true, message: '请选择模型' }]}>
        <Select loading={loading} showSearch optionFilterProp="label"
          onChange={() => form.setFieldValue('capabilityCode', undefined)}
          options={models.filter((item) => (!item.modelType || item.modelType.toUpperCase() === outputKind)
            && (!allowedModelCodes?.length || allowedModelCodes.includes(item.modelCode))).map((item) => {
            const billing = item.billing as { isFree?: boolean; billingDesc?: string } | undefined;
            return { value: item.modelCode, label: `${item.modelName || item.modelCode} · ${item.providerName || item.modelCode}`
              + ` · ${billing?.isFree ? '免费' : billing?.billingDesc || '价格未配置'}`,
              disabled: !item.available || !!item.modelType && item.modelType.toUpperCase() !== outputKind };
          })} />
      </Form.Item>
      {outputKind !== 'TEXT' && <Form.Item name="capabilityCode" label="真实模型能力"
        rules={[{ required: true, message: '请选择模型目录声明的能力编码' }]}
        extra={capabilities.length ? '能力编码来自当前模型目录。' : '此模型尚未返回可选能力，请先刷新模型目录配置。'}>
        <Select showSearch optionFilterProp="label" disabled={!capabilities.length}
          options={capabilities.map((item) => ({ value: item.code,
            label: `${item.label || item.code} · ${item.code}` }))} />
      </Form.Item>}
      <Form.Item name="projectId" label="项目 ID（可选）" rules={[
        { pattern: /^[1-9][0-9]*$/, message: '项目 ID 必须为正整数数字字符串' }
      ]}><Input inputMode="numeric" maxLength={30} /></Form.Item>
      {outputKind !== 'TEXT' && <Space wrap align="start">
        <Form.Item name="size" label="尺寸"><Input placeholder="如 1024x1024" /></Form.Item>
        <Form.Item name="aspectRatio" label="宽高比"><Input placeholder="如 16:9" /></Form.Item>
        {outputKind === 'IMAGE' && <Form.Item name="expectedImageCount" label="图片数量">
          <InputNumber min={1} max={100} precision={0} /></Form.Item>}
        {outputKind === 'VIDEO' && <Form.Item name="durationSeconds" label="时长（秒）">
          <InputNumber min={1} max={3600} precision={0} /></Form.Item>}
      </Space>}
    </Form>
    {receipt && <>
      <Typography.Title level={5}>提交回执</Typography.Title>
      <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }} items={[
        { key: 'runType', label: '运行类型', children: <Tag>{receipt.runType === 'GENERIC_RUNTIME'
          ? '通用 Runtime' : '委托 Skill Run'}</Tag> },
        { key: 'run', label: 'Run ID', children: <Typography.Text copyable>{runId}</Typography.Text> },
        { key: 'receipt', label: '回执状态', children: receipt.recoveryRequired
          ? <Tag color="warning">详情待恢复</Tag> : <Tag color="success">完整回执</Tag> },
        { key: 'request', label: '请求 ID', span: 2, children: <Typography.Text copyable>
          {receipt.requestClientRequestId}</Typography.Text> },
        ...(!receipt.recoveryRequired && receipt.runType === 'GENERIC_RUNTIME' ? [
          { key: 'status', label: '运行状态', children: <Tag>{receipt.status}</Tag> },
          { key: 'task', label: '媒体任务 ID', children: taskId
            ? <Typography.Text copyable>{taskId}</Typography.Text> : '尚无媒体任务' },
          { key: 'taskStatus', label: '媒体任务状态', children: receipt.task?.status || '尚未返回' },
          { key: 'billing', label: '运行计费状态', children: receipt.billingStatus || '尚未返回' },
          { key: 'cost', label: '运行实际费用', children: receipt.actualCost ?? '尚未返回' }
        ] : []),
        ...(!receipt.recoveryRequired && receipt.runType === 'SKILL_ORCHESTRATOR' ? [
          { key: 'status', label: '运行状态', children: <Tag>{receipt.status}</Tag> },
          { key: 'stage', label: '阶段', children: receipt.stage || '尚未返回' },
          { key: 'skill', label: '委托 Skill', children: receipt.skillCode || '尚未返回' },
          { key: 'version', label: 'Skill 版本 ID', children: receipt.skillVersionId == null
            ? '尚未返回' : <Typography.Text copyable>{receipt.skillVersionId}</Typography.Text> }
        ] : [])
      ]} />
      {completeGeneric && <RuntimeRunMedia run={completeGeneric} />}
      {!receipt.recoveryRequired && <>
        <Typography.Title level={5}>完整结果</Typography.Title>
        <StructuredValueEditor disabled value={JSON.stringify(receipt)} />
      </>}
    </>}
  </Modal>;
}
