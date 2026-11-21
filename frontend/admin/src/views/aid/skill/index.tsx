import { Link } from 'react-router-dom';
import React, { useCallback, useRef, useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Radio, Select,
  Space, Switch, Table, Tag, Typography, message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined, EditOutlined, EyeOutlined, HistoryOutlined, ReloadOutlined,
  SearchOutlined, UndoOutlined, PlusOutlined
} from '@ant-design/icons';

import {
  createSkill, editSkillIdentity, getSkillRun, getSkillVersion, listSkillRuns, listSkills, removeSkill, restoreSkill,
  updateSkillStatus, type AdminSkillSummary, type SkillRunItem,
  type SkillRunSummary, type SkillId, type SkillVersionDetail
} from '@/api/aid/skill';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import SkillPackageManager from './SkillPackageManager';
import StructuredValueEditor from './StructuredValueEditor';
import SkillRuntimeRunPanel from './SkillRuntimeRunPanel';
import { showReferenceConflict } from '@/utils/apiErrorDetails';
import { listBusinessGroups, saveBusinessGroupMember, type BusinessGroup } from '@/api/aid/businessGroup';
import ImageUpload from '@/components/ImageUpload';

interface IdentityFormValues {
  name: string;
  description?: string;
  capabilityDescription?: string;
  iconUrl?: string;
  visibility: 'PRIVATE' | 'PUBLIC';
  invocationScope: 'ENTRYPOINT' | 'INTERNAL';
  cacheToDisk?: boolean;
  status: '0' | '1';
}

interface TemplateOption {
  value: SkillId;
  label: string;
  skillId: SkillId;
  currentVersionId: SkillId;
  ownerType: string;
  visibility: string;
  invocationScope: string;
}

function templateVersionIssue(option: TemplateOption, version: SkillVersionDetail) {
  if (String(version.id) !== String(option.currentVersionId)
    || String(version.skillId) !== String(option.skillId) || !version.current) {
    return '模板当前版本已变化，请重新搜索并选择';
  }
  if (version.status !== '0') return '模板当前版本已停用';
  if (version.invocationScope !== option.invocationScope) return '模板版本与稳定身份调用范围不一致';
  const publicVersion = version.visibility === 'PUBLIC' && version.publishStatus === 'PUBLISHED';
  const privateVersion = version.visibility === 'PRIVATE' && version.publishStatus === 'PRIVATE';
  if (option.ownerType === 'PLATFORM') {
    if (version.visibility !== option.visibility || !(publicVersion || privateVersion)) {
      return '平台模板版本与稳定身份可见范围不一致';
    }
  } else if (option.ownerType === 'USER') {
    if (option.visibility !== 'PUBLIC' || !(publicVersion || privateVersion)) {
      return '用户模板必须是公开身份的正式当前版本';
    }
  } else {
    return '模板所有者类型不可复制';
  }
  if (!/^[0-9a-f]{64}$/.test(version.packageDigest || '') || !version.skillMarkdown?.trim()) {
    return '模板当前版本不是完整正式包';
  }
  return undefined;
}

function statusTag(status: string, delFlag?: string) {
  if (delFlag === '1') return <Tag color="error">已删除</Tag>;
  return status === '0' ? <Tag color="success">已启用</Tag> : <Tag>已停用</Tag>;
}

/** Skill 稳定身份、不可变版本包和运行审计入口。 */
export default function CommercialSkillPage({ authoringPath, editor: Editor, initialSkillId }: { authoringPath?: string; editor?: React.ComponentType<any>; initialSkillId?: string }) {
  const { hasPermi } = useAuth();
  const canList = hasPermi('aid:skill:list');
  const canQuery = hasPermi('aid:skill:query');
  const canEdit = hasPermi('aid:skill:edit');
  const canEditGroups = hasPermi('aid:businessGroup:query') && hasPermi('aid:businessGroup:edit');
  const [identityForm] = Form.useForm<IdentityFormValues>();
  const [createForm] = Form.useForm();
  const [queryForm] = Form.useForm();
  const [records, setRecords] = useState<AdminSkillSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [query, setQuery] = useState<{ keyword?: string; status?: '0' | '1' }>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [identityOpen, setIdentityOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createMode, setCreateMode] = useState<'blank' | 'template'>('blank');
  const [templateOptions, setTemplateOptions] = useState<TemplateOption[]>([]);
  const [createGroups, setCreateGroups] = useState<BusinessGroup[]>([]);
  const [templateLoading, setTemplateLoading] = useState(false);
  const [templateChecking, setTemplateChecking] = useState(false);
  const [validatedTemplateVersionId, setValidatedTemplateVersionId] = useState<SkillId>();
  const templateTimer = useRef<ReturnType<typeof setTimeout>>();
  const templateSequence = useRef(0);
  const templateValidationSequence = useRef(0);
  const templateCheckingRef = useRef(false);
  const validatedTemplateVersionIdRef = useRef<SkillId>();
  const [identitySaving, setIdentitySaving] = useState(false);
  const [identityCoverUploading, setIdentityCoverUploading] = useState(false);
  const [identitySyncing, setIdentitySyncing] = useState(false);
  const [editing, setEditing] = useState<AdminSkillSummary>();
  const [packageSkill, setPackageSkill] = useState<AdminSkillSummary>();
  const [actionKeys, setActionKeys] = useState<string[]>([]);
  const [runOpen, setRunOpen] = useState(false);
  const [runMode, setRunMode] = useState<'runtime' | 'legacy'>('runtime');
  const [runs, setRuns] = useState<SkillRunSummary[]>([]);
  const [runTotal, setRunTotal] = useState(0);
  const [runLoading, setRunLoading] = useState(false);
  const [runDetailLoadingId, setRunDetailLoadingId] = useState<SkillId>();
  const [runDetail, setRunDetail] = useState<SkillRunItem>();
  const [runSkillId, setRunSkillId] = useState<SkillId>();
  const [runtimeRunFocusId, setRuntimeRunFocusId] = useState<SkillId>();
  const [runPageNum, setRunPageNum] = useState(1);
  const [runPageSize, setRunPageSize] = useState(20);
  const listSequence = useRef(0);
  const runListSequence = useRef(0);
  const requestedRunDetailId = useRef<SkillId>();
  const runDetailSequence = useRef(0);
  const runContext = useRef('closed');
  const pendingActions = useRef(new Set<string>());
  const identitySavingRef = useRef(false);
  const identityCoverUploadingRef = useRef(false);
  const identitySyncingRef = useRef(false);
  const selectedTemplateVersionId = Form.useWatch('templateVersionId', createForm) as SkillId | undefined;

  const load = useCallback(async (force = false) => {
    if (!canList) return;
    const sequence = ++listSequence.current;
    setLoading(true);
    setError('');
    try {
      const response = await listSkills({ pageNum, pageSize, ...query }, { force });
      if (sequence !== listSequence.current) return;
      const rows = response.data || [];
      setRecords(rows);
      setTotal(Number(response.total || 0));
      return rows;
    } catch (cause: any) {
      if (sequence === listSequence.current) setError(cause?.message || 'Skill 列表加载失败');
    } finally {
      if (sequence === listSequence.current) setLoading(false);
    }
  }, [canList, pageNum, pageSize, query]);

  React.useEffect(() => { if (!Editor) void load(); }, [load, Editor]);

  const runAction = async (key: string, operation: () => Promise<void>) => {
    if (pendingActions.current.has(key)) return;
    pendingActions.current.add(key);
    setActionKeys((current) => [...current, key]);
    try {
      await operation();
    } finally {
      pendingActions.current.delete(key);
      setActionKeys((current) => current.filter((item) => item !== key));
    }
  };

  const identityBusy = identitySaving || identityCoverUploading || identitySyncing;

  const identityValues = (record: AdminSkillSummary): IdentityFormValues => ({
    name: record.name,
    description: record.description,
    capabilityDescription: record.capabilityDescription,
    iconUrl: record.iconUrl,
    visibility: record.visibility as 'PRIVATE' | 'PUBLIC',
    invocationScope: record.invocationScope as 'ENTRYPOINT' | 'INTERNAL',
    cacheToDisk: record.cacheToDisk,
    status: record.status
  });

  const openIdentity = (record: AdminSkillSummary) => {
    identitySavingRef.current = false;
    identityCoverUploadingRef.current = false;
    identitySyncingRef.current = false;
    setIdentityCoverUploading(false);
    setIdentitySyncing(false);
    setEditing(record);
    identityForm.setFieldsValue(identityValues(record));
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
    if (!editing || identitySavingRef.current) return;
    if (identityCoverUploadingRef.current || identitySyncingRef.current) {
      message.warning('封面正在上传或同步，请稍候再保存身份信息');
      return;
    }
    const editingId = editing.id;
    identitySavingRef.current = true;
    setIdentitySaving(true);
    try {
      const values = await identityForm.validateFields();
      await editSkillIdentity({ id: editingId, ...values,
        cacheToDisk: editing.ownerType === 'PLATFORM'
          ? values.cacheToDisk : false });
      const rows = await load(true);
      const authoritative = rows?.find((item) => String(item.id) === String(editingId));
      if (authoritative) {
        setEditing(authoritative);
        identityForm.setFieldsValue(identityValues(authoritative));
      }
      message.success('稳定身份已保存');
      setIdentityOpen(false);
    } catch (cause: any) {
      message.error(cause?.message || '身份保存失败');
    } finally {
      identitySavingRef.current = false;
      setIdentitySaving(false);
    }
  };

  const saveNewSkill = async () => {
    if (creating || templateCheckingRef.current) return;
    setCreating(true);
    try {
      const values = await createForm.validateFields();
      if (createMode === 'template'
        && String(validatedTemplateVersionIdRef.current ?? '') !== String(values.templateVersionId ?? '')) {
        createForm.setFields([{ name: 'templateVersionId', errors: ['请重新选择并等待正式版本校验'] }]);
        return;
      }
      const response = await createSkill({
        skillCode: values.skillCode,
        name: values.name,
        description: values.description,
        visibility: 'PRIVATE',
        invocationScope: values.invocationScope,
        templateVersionId: createMode === 'template' ? values.templateVersionId : undefined });
      let groupFailure = false;
      for (const groupId of (canEditGroups ? values.groupIds || [] : []) as SkillId[]) {
        try {
          await saveBusinessGroupMember({ groupId, memberType: 'SKILL',
            memberId: response.data.id, sortOrder: 0 });
        } catch { groupFailure = true; }
      }
      if (groupFailure) message.warning('Skill 已创建，部分业务分组关联失败；可在业务分组页补齐');
      message.success('Skill 已创建，可继续填写草稿并发布');
      closeCreate();
      createForm.resetFields();
      await load(true);
      setPackageSkill(response.data);
    } catch (cause: any) {
      if (!cause?.errorFields) message.error(cause?.message || '创建失败');
    } finally { setCreating(false); }
  };

  const searchTemplates = async (keyword = '') => {
    const sequence = ++templateSequence.current;
    setTemplateLoading(true);
    try {
      const rows: AdminSkillSummary[] = [];
      for (let pageNum = 1; ; pageNum += 1) {
        const response = await listSkills({ pageNum, pageSize: 100, keyword: keyword || undefined });
        if (sequence !== templateSequence.current) return;
        const page = response.data || [];
        rows.push(...page);
        const total = Number(response.total);
        if (page.length === 0 || page.length < 100
          || (Number.isFinite(total) && total >= 0 && rows.length >= total)) break;
      }
      if (sequence !== templateSequence.current) return;
      const seenVersions = new Set<string>();
      setTemplateOptions(rows.filter((item) => item.delFlag === '0' && item.currentVersionId != null
          && (item.visibility === 'PUBLIC'
            || (item.ownerType === 'PLATFORM' && item.visibility === 'PRIVATE')))
        .filter((item) => {
          const key = String(item.currentVersionId);
          if (seenVersions.has(key)) return false;
          seenVersions.add(key);
          return true;
        })
        .map((item) => ({ value: item.currentVersionId!, skillId: item.id,
          currentVersionId: item.currentVersionId!, visibility: item.visibility,
          ownerType: item.ownerType, invocationScope: item.invocationScope,
          label: `${item.name} · ${item.skillCode}（版本 #${item.currentVersionId} · ${
            item.visibility === 'PUBLIC' ? '公开' : '平台私有'}）` })));
    } catch (cause: any) {
      if (sequence === templateSequence.current) message.error(cause?.message || '模板加载失败');
    } finally {
      if (sequence === templateSequence.current) setTemplateLoading(false);
    }
  };

  const validateTemplateVersion = async (versionId?: SkillId) => {
    const sequence = ++templateValidationSequence.current;
    validatedTemplateVersionIdRef.current = undefined;
    setValidatedTemplateVersionId(undefined);
    createForm.setFields([{ name: 'templateVersionId', errors: [] }]);
    if (versionId == null) return;
    const option = templateOptions.find((item) => String(item.value) === String(versionId));
    if (!option) {
      createForm.setFields([{ name: 'templateVersionId', errors: ['模板不在当前可复制范围内'] }]);
      return;
    }
    templateCheckingRef.current = true;
    setTemplateChecking(true);
    try {
      const version = (await getSkillVersion(versionId, { force: true })).data;
      if (sequence !== templateValidationSequence.current) return;
      const issue = templateVersionIssue(option, version);
      if (issue) {
        createForm.setFields([{ name: 'templateVersionId', errors: [issue] }]);
        return;
      }
      validatedTemplateVersionIdRef.current = versionId;
      setValidatedTemplateVersionId(versionId);
    } catch (cause: any) {
      if (sequence === templateValidationSequence.current) {
        createForm.setFields([{ name: 'templateVersionId',
          errors: [cause?.message || '模板正式版本读取失败'] }]);
      }
    } finally {
      if (sequence === templateValidationSequence.current) {
        templateCheckingRef.current = false;
        setTemplateChecking(false);
      }
    }
  };

  function closeCreate() {
    templateSequence.current += 1;
    templateValidationSequence.current += 1;
    templateCheckingRef.current = false;
    validatedTemplateVersionIdRef.current = undefined;
    if (templateTimer.current) clearTimeout(templateTimer.current);
    setTemplateLoading(false);
    setTemplateChecking(false);
    setValidatedTemplateVersionId(undefined);
    setCreateOpen(false);
  }

  const loadCreateGroups = async () => {
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

  const changeStatus = (row: AdminSkillSummary, status: '0' | '1') => runAction(
    `status:${row.id}:${status}`, async () => {
      try {
        await updateSkillStatus(row.id, status);
        message.success(status === '0' ? 'Skill 已启用' : 'Skill 已停用');
        await load(true);
      } catch (cause: any) {
        message.error(cause?.message || '状态更新失败');
      }
    }
  );

  const deleteSkill = (row: AdminSkillSummary) => runAction(`remove:${row.id}`, async () => {
    try {
      await removeSkill(row.id);
      message.success('已软删除');
      await load(true);
    } catch (cause: any) {
      if (!showReferenceConflict(cause, 'Skill 仍有有效引用')) message.error(cause?.message || '删除失败');
    }
  });

  const recoverSkill = (row: AdminSkillSummary) => runAction(`restore:${row.id}`, async () => {
    try {
      await restoreSkill(row.id);
      message.success('已恢复并停用');
      await load(true);
    } catch (cause: any) {
      message.error(cause?.message || '恢复失败');
    }
  });

  const loadRuns = async (skillId?: SkillId, page = 1, size = 20, force = false) => {
    const sequence = ++runListSequence.current;
    setRunLoading(true);
    try {
      const response = await listSkillRuns({ pageNum: page, pageSize: size, skillId }, { force });
      if (sequence !== runListSequence.current) return;
      setRuns(response.data || []);
      setRunTotal(Number(response.total || 0));
    } catch (cause: any) {
      if (sequence === runListSequence.current) message.error(cause?.message || '运行记录加载失败');
    } finally {
      if (sequence === runListSequence.current) setRunLoading(false);
    }
  };

  const closeRunDetail = () => {
    runDetailSequence.current += 1;
    requestedRunDetailId.current = undefined;
    setRunDetailLoadingId(undefined);
    setRunDetail(undefined);
  };

  const openRuns = async (skillId?: SkillId, focusRunId?: SkillId) => {
    runContext.current = skillId == null ? 'all' : `skill:${String(skillId)}`;
    closeRunDetail();
    setRuntimeRunFocusId(focusRunId);
    setRunOpen(true);
    setRunMode('runtime');
    setRunSkillId(skillId);
    setRunPageNum(1);
    setRunPageSize(20);
  };

  const closeRuns = () => {
    runContext.current = 'closed';
    runListSequence.current += 1;
    setRunLoading(false);
    setRuntimeRunFocusId(undefined);
    closeRunDetail();
    setRunOpen(false);
  };

  const showRunDetail = async (id: SkillId) => {
    if (runContext.current === 'closed' || String(runDetailLoadingId) === String(id)) return;
    const context = runContext.current;
    const sequence = ++runDetailSequence.current;
    requestedRunDetailId.current = id;
    setRunDetailLoadingId(id);
    try {
      const response = await getSkillRun(id);
      if (sequence !== runDetailSequence.current || runContext.current !== context
        || String(requestedRunDetailId.current) !== String(id)) return;
      setRunDetail(response.data);
    } catch (cause: any) {
      if (sequence === runDetailSequence.current && runContext.current === context
        && String(requestedRunDetailId.current) === String(id)) {
        message.error(cause?.message || '运行详情加载失败');
      }
    } finally {
      if (sequence === runDetailSequence.current && runContext.current === context
        && String(requestedRunDetailId.current) === String(id)) setRunDetailLoadingId(undefined);
    }
  };

  const columns: ColumnsType<AdminSkillSummary> = [
    { title: '稳定身份', dataIndex: 'name', render: (_, row) => <Space direction="vertical" size={0}>
      <Typography.Text strong>{row.name}</Typography.Text>
      <Typography.Text type="secondary" copyable>{row.skillCode}</Typography.Text>
    </Space> },
    { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '--' },
    { title: '能力介绍', dataIndex: 'capabilityDescription', ellipsis: true,
      render: (value) => value || '--' },
    { title: '当前版本 ID', dataIndex: 'currentVersionId', width: 120, render: (value) => value || '--' },
    { title: '当前模型', dataIndex: 'modelCode', width: 180, render: (value) => value || '--' },
    { title: '状态', dataIndex: 'status', width: 90, render: (_, row) => statusTag(row.status, row.delFlag) },
    { title: '更新时间', dataIndex: 'updateTime', width: 170, render: (value) => value || '--' },
    { title: '操作', key: 'action', width: 485, fixed: 'right', render: (_, row) => <Space size={4}>
      {hasPermi('aid:skill:run:list') && <Button type="link" icon={<EyeOutlined />}
        onClick={() => openRuns(row.id)}>运行</Button>}
      {authoringPath && canList && canQuery && row.delFlag === '0' && (
        <Link to={authoringPath + '&skillId=' + encodeURIComponent(String(row.id))}>编制</Link>
      )}
      {canQuery && row.delFlag === '0' && <Button type="link" icon={<HistoryOutlined />}
        onClick={() => setPackageSkill(row)}>{row.currentVersionId ? '版本' : '版本化'}</Button>}
      {canQuery && <Button type="link" icon={canEdit ? <EditOutlined /> : <EyeOutlined />}
        onClick={() => openIdentity(row)}>身份</Button>}
      {row.delFlag === '0' && canEdit && <Switch checked={row.status === '0'}
        loading={actionKeys.some((key) => key.startsWith(`status:${row.id}:`))}
        checkedChildren="启" unCheckedChildren="停"
        onChange={(checked) => changeStatus(row, checked ? '0' : '1')} />}
      {row.delFlag === '0' && hasPermi('aid:skill:remove') && <Popconfirm
        title="删除后不能发起新运行，历史仍可审计。确认删除？" onConfirm={() => deleteSkill(row)}>
        <Button danger type="link" loading={actionKeys.includes(`remove:${row.id}`)}
          icon={<DeleteOutlined />}>删除</Button>
      </Popconfirm>}
      {row.delFlag === '1' && hasPermi('aid:skill:restore') && <Popconfirm
        title="恢复后保持停用，确认恢复？" onConfirm={() => recoverSkill(row)}>
        <Button type="link" loading={actionKeys.includes(`restore:${row.id}`)}
          icon={<UndoOutlined />}>恢复</Button>
      </Popconfirm>}
    </Space> }
  ];

  return <div>
    {Editor ? <Editor scope="skill" initialSkillId={initialSkillId}
      onRuns={hasPermi('aid:skill:run:list') ? openRuns : undefined}
      onCreateSkill={canEdit ? () => {
        createForm.setFieldsValue({ visibility: 'PRIVATE', invocationScope: 'ENTRYPOINT' });
        setCreateMode('blank'); setCreateOpen(true);
        if (canEditGroups) void loadCreateGroups();
      } : undefined} /> : <><PageHeader title="Skill 管理" />
    <Card>
      <Space wrap style={{ marginBottom: 16 }}>
        {canEdit && <Button type="primary" icon={<PlusOutlined />}
          onClick={() => { createForm.setFieldsValue({ visibility: 'PRIVATE', invocationScope: 'ENTRYPOINT' });
            setCreateMode('blank'); setCreateOpen(true);
            if (canEditGroups) void loadCreateGroups(); }}>
          新建 Skill
        </Button>}
        {hasPermi('aid:skill:run:list') && <Button icon={<EyeOutlined />}
          onClick={() => openRuns(undefined)}>全部运行审计</Button>}
        <Form form={queryForm} layout="inline" onFinish={(values) => {
          setQuery({ keyword: values.keyword?.trim(), status: values.status });
          setPageNum(1);
        }}>
          <Form.Item name="keyword"><Input allowClear prefix={<SearchOutlined />}
            placeholder="名称或编码" /></Form.Item>
          <Form.Item name="status"><Select allowClear placeholder="状态" style={{ width: 120 }} options={[
            { label: '已启用', value: '0' }, { label: '已停用', value: '1' }
          ]} /></Form.Item>
          <Button type="primary" htmlType="submit">查询</Button>
        </Form>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(true)}>刷新</Button>
      </Space>
      {error && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={error} />}
      <Table rowKey="id" loading={loading} columns={columns} dataSource={records} scroll={{ x: 1350 }}
        pagination={{ current: pageNum, pageSize, total, showSizeChanger: true,
          onChange: (page, size) => { setPageNum(page); setPageSize(size); } }} />
    </Card></>}

    <Modal open={createOpen} title="新建 Skill · 基本信息" width={620} destroyOnClose style={{ top: 24 }}
      styles={{ body: { maxHeight: 'calc(100vh - 220px)', overflowY: 'auto' } }}
      okText="创建并配置" confirmLoading={creating} onOk={saveNewSkill}
      okButtonProps={{ disabled: templateChecking || (createMode === 'template'
        && String(validatedTemplateVersionId ?? '') !== String(selectedTemplateVersionId ?? '')) }}
      onCancel={closeCreate}>
      <Alert type="info" showIcon style={{ marginBottom: 16 }} message="先建立稳定身份"
        description="创建后继续填写提示词、输出要求和模型，再保存草稿、校验及发布。默认保持私有。" />
      <Form form={createForm} layout="vertical">
        <Form.Item label="创建方式">
          <Radio.Group value={createMode} optionType="button" buttonStyle="solid"
            options={[{ value: 'blank', label: '空白新建' }, { value: 'template', label: '从模板复制' }]}
            onChange={(event) => { templateValidationSequence.current += 1;
              templateCheckingRef.current = false; validatedTemplateVersionIdRef.current = undefined;
              setTemplateChecking(false); setValidatedTemplateVersionId(undefined);
              setCreateMode(event.target.value); createForm.setFieldValue('templateVersionId', undefined);
              if (event.target.value === 'template') void searchTemplates(); }} />
        </Form.Item>
        {createMode === 'template' && <Form.Item name="templateVersionId" label="模板版本"
          extra="仅显示公开正式版本和平台私有正式版本；用户私有 Skill 不会进入模板列表，复制结果仍保持私有。"
          rules={[{ required: true, message: '请选择正式模板' }]}>
          <Select showSearch filterOption={false} loading={templateLoading || templateChecking}
            placeholder="输入名称或编码搜索正式 Skill" options={templateOptions}
            onChange={(value) => void validateTemplateVersion(value)}
            onSearch={(value) => { if (templateTimer.current) clearTimeout(templateTimer.current);
              templateTimer.current = setTimeout(() => void searchTemplates(value.trim()), 250); }} />
        </Form.Item>}
        <Form.Item name="skillCode" label="稳定编码" rules={[{ required: true, message: '请输入编码' },
          { pattern: /^[a-z][a-z0-9_]{1,63}$/, message: '以小写字母开头，2–64 位，仅可使用小写字母、数字和下划线' }]}>
          <Input maxLength={64} placeholder="例如 character_turnaround" />
        </Form.Item>
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input maxLength={100} placeholder="例如 角色三视图" />
        </Form.Item>
        <Form.Item name="description" label="介绍"><Input.TextArea rows={3} maxLength={1000} showCount /></Form.Item>
        <Alert type="info" showIcon style={{ marginBottom: 16 }}
          message="创建后上传封面并设置公开"
          description="封面上传必须绑定已存在的 Skill ID。新身份固定先以私有状态创建，再到稳定身份中上传封面并切换可见范围。" />
        {canEditGroups && <Form.Item name="groupIds" label="业务分组" extra="同一 Skill 可加入多个分组，分组关系与模型功能池独立。">
          <Select mode="multiple" showSearch optionFilterProp="label" placeholder="选择业务分组"
            options={createGroups.map((group) => ({ value: group.id,
              label: `${group.name} · ${group.groupCode}` }))} />
        </Form.Item>}
        <Form.Item name="visibility" label="创建可见范围"><Select disabled options={[{ label: '私有', value: 'PRIVATE' }]} /></Form.Item>
        <Form.Item name="invocationScope" label="调用方式" rules={[{ required: true }]}>
          <Select options={[{ label: '可直接运行', value: 'ENTRYPOINT' },
            { label: '仅作为内部子能力', value: 'INTERNAL' }]} />
        </Form.Item>
      </Form>
    </Modal>

    <Drawer width="min(720px, 100vw)" title={editing ? `${editing.name} · 稳定身份` : 'Skill 稳定身份'}
      open={identityOpen} onClose={closeIdentity} destroyOnClose maskClosable={!identityBusy} keyboard={!identityBusy}
      extra={editing?.delFlag === '0' && canEdit ? <Button type="primary"
        loading={identitySaving || identitySyncing} disabled={identityBusy} onClick={saveIdentity}>保存身份</Button> : null}>
      <Alert type="info" showIcon message="身份配置与可执行版本相互独立"
        description="这里维护名称、说明、图标和总开关；Prompt、Schema、资源及路由请在版本包草稿中修改。" />
      {editing && <Descriptions bordered size="small" column={2} style={{ marginTop: 16, marginBottom: 16 }} items={[
        { key: 'code', label: '稳定编码', children: <Typography.Text copyable>{editing.skillCode}</Typography.Text> },
        { key: 'owner', label: '所有者', children: editing.ownerType },
        { key: 'scope', label: '调用范围', children: editing.invocationScope },
        { key: 'visibility', label: '可见范围', children: editing.visibility },
        { key: 'version', label: '当前版本 ID', children: editing.currentVersionId || '--' }
      ]} />}
      <Form form={identityForm} layout="vertical" disabled={editing?.delFlag === '1' || !canEdit || identityBusy}>
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input maxLength={100} /></Form.Item>
        <Form.Item name="description" label="说明"><Input.TextArea rows={4} maxLength={1000} showCount /></Form.Item>
        <Form.Item name="capabilityDescription" label="能力介绍"
          tooltip="直接返回给 C 端 Skill 目录，不使用硬编码回退">
          <Input.TextArea rows={5} maxLength={2000} showCount />
        </Form.Item>
        <Form.Item noStyle shouldUpdate={(previous, current) => previous.visibility !== current.visibility}>
          {({ getFieldValue }) => <Form.Item name="iconUrl" label="封面"
            extra={getFieldValue('visibility') === 'PUBLIC'
              ? '公开 Skill 必须上传有效封面；上传完成会立即绑定当前 Skill。'
              : '私有 Skill 可选；上传完成会立即绑定当前 Skill。'}
            rules={[{ validator: (_, value) => getFieldValue('visibility') === 'PUBLIC' && !value
              ? Promise.reject(new Error('公开 Skill 必须上传封面')) : Promise.resolve() }]}>
            <ImageUpload action={`${import.meta.env.VITE_APP_BASE_API || ''}/aid/skill/cover/upload`}
              extraData={{ skillId: String(editing?.id || '') }} name="file" maxCount={1} maxSize={5} accept="image/*"
              disabled={identityBusy}
              onUploadingChange={(uploading) => {
                identityCoverUploadingRef.current = uploading;
                setIdentityCoverUploading(uploading);
              }}
              onUploadSuccess={async (url) => {
                if (!editing) return;
                const skillId = editing.id;
                identitySyncingRef.current = true;
                setIdentitySyncing(true);
                identityForm.setFieldValue('iconUrl', url);
                setEditing((current) => current && String(current.id) === String(skillId)
                  ? { ...current, iconUrl: url } : current);
                setRecords((current) => current.map((item) =>
                  String(item.id) === String(skillId) ? { ...item, iconUrl: url } : item));
                try {
                  const rows = await load(true);
                  const authoritative = rows?.find((item) => String(item.id) === String(skillId));
                  if (authoritative) {
                    setEditing((current) => current && String(current.id) === String(skillId)
                      ? authoritative : current);
                    identityForm.setFieldValue('iconUrl', authoritative.iconUrl);
                  }
                } finally {
                  identitySyncingRef.current = false;
                  setIdentitySyncing(false);
                }
              }} />
          </Form.Item>}
        </Form.Item>
        <Form.Item name="visibility" label="可见范围" rules={[{ required: true }]}
          extra={editing?.ownerType === 'USER'
            ? '用户 Skill 的公开策略与审计下架请在内部“Agent / Skill 编制”页处理。' : undefined}>
          <Select disabled={editing?.ownerType === 'USER'}
            options={[{ label: '私有', value: 'PRIVATE' }, { label: '公开', value: 'PUBLIC' }]} />
        </Form.Item>
        <Form.Item name="invocationScope" label="调用方式" rules={[{ required: true }]}>
          <Select options={[{ label: '可直接运行', value: 'ENTRYPOINT' },
            { label: '仅作为内部子能力', value: 'INTERNAL' }]} />
        </Form.Item>
        {editing?.ownerType === 'PLATFORM'
          ? <Form.Item name="cacheToDisk" label="缓存到本地磁盘" valuePropName="checked"><Switch /></Form.Item>
          : <Form.Item label="缓存到本地磁盘" extra="仅平台 Skill 可开启"><Switch checked={false} disabled /></Form.Item>}
        <Form.Item name="status" label="总开关" rules={[{ required: true }]}><Select options={[
          { label: '启用', value: '0' }, { label: '停用', value: '1' }
        ]} /></Form.Item>
      </Form>
    </Drawer>

    <SkillPackageManager open={!!packageSkill} skill={packageSkill} canEdit={canEdit}
      onClose={() => setPackageSkill(undefined)} onChanged={async () => { await load(true); }}
      onOpenRunRecords={hasPermi('aid:skill:run:list') ? (runId) => {
        const skillId = packageSkill?.id;
        setPackageSkill(undefined);
        void openRuns(skillId, hasPermi('aid:skill:run:query') ? runId : undefined);
      } : undefined} />

    <Drawer width="min(1120px, 100vw)" title="Skill 运行记录" open={runOpen}
      onClose={closeRuns}
      extra={runMode === 'legacy' && <Button icon={<ReloadOutlined />} loading={runLoading}
        onClick={() => loadRuns(runSkillId, runPageNum, runPageSize, true)}>刷新</Button>}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Button type={runMode === 'runtime' ? 'primary' : 'default'}
          onClick={() => { closeRunDetail(); setRuntimeRunFocusId(undefined); setRunMode('runtime'); }}>
          通用 Runtime</Button>
        <Button type={runMode === 'legacy' ? 'primary' : 'default'} onClick={() => {
          closeRunDetail(); setRuntimeRunFocusId(undefined); setRunMode('legacy'); void loadRuns(runSkillId, 1, 20);
        }}>历史 SkillRun</Button>
      </Space>
      {runOpen && (runMode === 'runtime' ? <SkillRuntimeRunPanel skillId={runSkillId}
        initialRunId={runtimeRunFocusId} /> : <Table
        rowKey="id" loading={runLoading} dataSource={runs} scroll={{ x: 1240 }}
        pagination={{ current: runPageNum, pageSize: runPageSize, total: runTotal, showSizeChanger: true,
          onChange: (page, size) => { setRunPageNum(page); setRunPageSize(size); loadRuns(runSkillId, page, size); } }}
        columns={[
          { title: 'Run ID', dataIndex: 'id', width: 90 }, { title: '用户 ID', dataIndex: 'userId', width: 90 },
          { title: '版本 ID', dataIndex: 'skillVersionId', width: 90 },
          { title: '项目/剧集', width: 120, render: (_: unknown, row: SkillRunSummary) =>
            row.projectId == null ? '未关联剧集' : `${row.projectId}/${row.episodeId ?? '—'}` },
          { title: '动作', dataIndex: 'actionMode', width: 100, render: (value?: string) => value || '--' },
          { title: '阶段', dataIndex: 'stage', width: 110, render: (value?: string) => value || '--' },
          { title: '状态', dataIndex: 'status', width: 110, render: (value: string) => <Tag>{value}</Tag> },
          { title: '模型', dataIndex: 'modelCode', width: 170 },
          { title: '耗时', dataIndex: 'durationMillis', width: 110,
            render: (value?: number) => value == null ? '--' : `${value} ms` },
          { title: '开始', dataIndex: 'startedAt', width: 170 },
          { title: '操作', width: 90, fixed: 'right', render: (_: unknown, row: SkillRunSummary) =>
            hasPermi('aid:skill:run:query') ? <Button type="link"
              loading={String(runDetailLoadingId) === String(row.id)} onClick={() => showRunDetail(row.id)}>详情</Button> : null }
        ]} />)}
      <Modal width={780} title="Run 详情" open={!!runDetail} footer={null}
        onCancel={closeRunDetail}>
        {runDetail && <Descriptions column={1} bordered size="small" items={[
          { key: 'id', label: 'Run ID', children: runDetail.id },
          { key: 'status', label: '状态', children: runDetail.status },
          { key: 'scope', label: '项目 / 剧集', children:
            runDetail.projectId == null ? '未关联剧集' : `${runDetail.projectId} / ${runDetail.episodeId ?? '—'}` },
          { key: 'version', label: 'Skill 版本 ID', children: runDetail.skillVersionId },
          { key: 'mode', label: '动作 / 质量 / 阶段', children:
            `${runDetail.actionMode || '--'} / ${runDetail.qualityMode || '--'} / ${runDetail.stage || '--'}` },
          { key: 'hash', label: '执行配置摘要', children: <Typography.Text copyable>
            {runDetail.skillConfigHash || '--'}</Typography.Text> },
          { key: 'tasks', label: '步骤与计费任务', children: <Table size="small" pagination={false}
            rowKey="stepId" dataSource={runDetail.tasks || []} scroll={{ x: 760 }} columns={[
              { title: '步骤', dataIndex: 'stepKey', width: 120 },
              { title: '动作', dataIndex: 'actionMode', width: 100 },
              { title: '编排状态', dataIndex: 'orchestrationStatus', width: 130 },
              { title: '媒体任务', dataIndex: 'mediaTaskId', width: 100,
                render: (value?: SkillId) => value || '--' },
              { title: '任务状态', dataIndex: 'mediaStatus', width: 110,
                render: (value?: string) => value || '--' },
              { title: '计费状态', dataIndex: 'billingStatus', width: 110,
                render: (value?: string) => value || '--' },
              { title: '实际费用', dataIndex: 'actualCost', width: 100,
                render: (value?: string) => value ?? '--' }
            ]} /> },
          { key: 'error', label: '错误', children: runDetail.errorMessage || '--' },
          { key: 'input', label: '输入', children: <StructuredValueEditor disabled
            value={runDetail.inputJson || '{}'} /> },
          { key: 'output', label: '输出', children: <StructuredValueEditor disabled
            value={runDetail.outputJson || '{}'} /> }
        ]} />}
      </Modal>
    </Drawer>
  </div>;
}
