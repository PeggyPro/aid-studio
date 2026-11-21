import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  Upload,
  message
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  UploadOutlined
} from '@ant-design/icons';
import { useAuth } from '@/hooks/useAuth';
import { getToken } from '@/utils/auth';
import {
  listCanvasModels,
  listCanvasModelCategories,
  previewCanvasModel,
  editCanvasModel,
  removeCanvasModel,
  saveCanvasModelCategory,
  uploadCanvasModel,
  type CanvasLibraryModel,
  type CanvasModelCategory,
  type CanvasModelEdit,
  type CanvasModelQuery
} from '@/api/aid/canvasModels';
import ModelPreview, { type PreparedModel } from './ModelPreview';
import { MAX_MODEL_BYTES } from './glbPreview';

const labels = { DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' };
const colors = { DRAFT: 'default', PUBLISHED: 'green', DISABLED: 'orange' };
const failure = (error: unknown) => (error instanceof Error ? error.message : '操作失败，请稍后重试');
const editInput = (row: CanvasLibraryModel): CanvasModelEdit => ({
  id: row.id,
  revision: row.revision,
  name: row.name,
  categoryId: row.categoryId,
  tags: row.tags ?? '',
  status: row.status
});

export default function CanvasModelsPage() {
  const { hasPermi } = useAuth(),
    token = getToken();
  const canList = hasPermi('aid:canvasModel:list'),
    canQuery = hasPermi('aid:canvasModel:query');
  const canAdd = hasPermi('aid:canvasModel:add'),
    canEdit = hasPermi('aid:canvasModel:edit'),
    canRemove = hasPermi('aid:canvasModel:remove');
  const [rows, setRows] = useState<CanvasLibraryModel[]>([]),
    [categories, setCategories] = useState<CanvasModelCategory[]>([]);
  const [query, setQuery] = useState<CanvasModelQuery>({ pageNum: 1, pageSize: 20 });
  const [loading, setLoading] = useState(false),
    [error, setError] = useState(''),
    [reload, setReload] = useState(0);
  const [saving, setSaving] = useState(false),
    writeLock = useRef(false),
    mounted = useRef(true),
    listEpoch = useRef(0);
  const [searchForm] = Form.useForm(),
    [editForm] = Form.useForm(),
    [uploadForm] = Form.useForm(),
    [categoryForm] = Form.useForm();
  const [editing, setEditing] = useState<CanvasLibraryModel | null>(null);
  const [uploading, setUploading] = useState<{ modelId?: string; name?: string; categoryId?: string } | null>(null);
  const [file, setFile] = useState<File | null>(null),
    [prepared, setPrepared] = useState<PreparedModel | null>(null);
  const [categoryOpen, setCategoryOpen] = useState(false),
    [categoryEditing, setCategoryEditing] = useState<CanvasModelCategory | null>(null);
  const [preview, setPreview] = useState<{ row: CanvasLibraryModel; file?: Blob; error?: string } | null>(null);
  const previewRequest = useRef<AbortController | null>(null),
    previewEpoch = useRef(0);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      previewRequest.current?.abort();
    };
  }, []);
  useEffect(() => {
    const epoch = ++listEpoch.current;
    setRows([]);
    setCategories([]);
    setError('');
    setEditing(null);
    setUploading(null);
    setFile(null);
    setPrepared(null);
    setPreview(null);
    previewRequest.current?.abort();
    previewEpoch.current++;
    if (!canList) return;
    setLoading(true);
    void Promise.all([listCanvasModels(query), listCanvasModelCategories()])
      .then(([models, groups]) => {
        if (
          mounted.current &&
          epoch === listEpoch.current &&
          token === getToken() &&
          hasPermi('aid:canvasModel:list')
        ) {
          setRows(models);
          setCategories(groups.filter((category) => category.status !== 'DELETED'));
        }
      })
      .catch((reason) => {
        if (mounted.current && epoch === listEpoch.current && token === getToken()) setError(failure(reason));
      })
      .finally(() => {
        if (mounted.current && epoch === listEpoch.current) setLoading(false);
      });
  }, [canList, hasPermi, query, reload, token]);
  useEffect(() => {
    if (!canQuery) {
      previewRequest.current?.abort();
      previewEpoch.current++;
      setPreview(null);
    }
  }, [canQuery]);

  const perform = async (permission: string, action: (guard: () => void) => Promise<unknown>) => {
    if (writeLock.current || !hasPermi(permission)) return;
    const identity = getToken();
    writeLock.current = true;
    setSaving(true);
    const guard = () => {
      if (!mounted.current || identity !== getToken() || !hasPermi(permission))
        throw new Error('登录身份或权限已变化，请重新操作');
    };
    try {
      guard();
      await action(guard);
      guard();
      if (mounted.current && identity === getToken()) {
        message.success('操作成功');
        setReload((value) => value + 1);
      }
    } catch (reason) {
      if (
        mounted.current &&
        identity === getToken() &&
        !(reason && typeof reason === 'object' && 'errorFields' in reason)
      )
        message.error(failure(reason));
    } finally {
      writeLock.current = false;
      if (mounted.current) setSaving(false);
    }
  };
  const openUpload = (row?: CanvasLibraryModel) => {
    if (!hasPermi('aid:canvasModel:add') || writeLock.current) return;
    setUploading(row ? { modelId: row.modelId, name: row.name, categoryId: row.categoryId } : {});
    setFile(null);
    setPrepared(null);
    uploadForm.resetFields();
    uploadForm.setFieldsValue({ name: row?.name ?? '', categoryId: row?.categoryId });
  };
  const openPreview = async (row: CanvasLibraryModel) => {
    if (!hasPermi('aid:canvasModel:query')) return;
    const epoch = ++previewEpoch.current,
      identity = getToken();
    previewRequest.current?.abort();
    const controller = new AbortController();
    previewRequest.current = controller;
    setPreview({ row });
    try {
      const descriptor = await previewCanvasModel(row.id);
      if (
        controller.signal.aborted ||
        epoch !== previewEpoch.current ||
        identity !== getToken() ||
        !hasPermi('aid:canvasModel:query')
      )
        return;
      if (descriptor.id !== row.id) throw new Error('模型版本已变化，请重新打开');
      const url = new URL(descriptor.fileUrl, window.location.href);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
        throw new Error('模型预览地址无效');
      const response = await fetch(url, { signal: controller.signal, credentials: 'omit' });
      if (!response.ok) throw new Error('模型读取失败，请重新打开预览');
      if (Number(response.headers.get('content-length')) > MAX_MODEL_BYTES)
        throw new Error('模型超过浏览器预览大小限制');
      const content = await response.blob();
      if (
        !controller.signal.aborted &&
        mounted.current &&
        epoch === previewEpoch.current &&
        identity === getToken() &&
        hasPermi('aid:canvasModel:query')
      )
        setPreview({ row, file: content });
    } catch (reason) {
      if (!controller.signal.aborted && mounted.current && epoch === previewEpoch.current && identity === getToken())
        setPreview({ row, error: failure(reason) });
    }
  };
  const closePreview = () => {
    previewEpoch.current++;
    previewRequest.current?.abort();
    setPreview(null);
  };
  const activeCategories = categories.filter((category) => category.status === 'ACTIVE');
  const preparedCallback = useCallback((value: PreparedModel | null) => setPrepared(value), []);

  if (!canList) return <Alert type="warning" showIcon message="当前账号没有模型库查看权限" />;
  return (
    <Card
      title="画布模型库"
      extra={
        <Space wrap>
          {canEdit && <Button onClick={() => setCategoryOpen(true)}>管理分类</Button>}
          {canAdd && (
            <Button type="primary" icon={<PlusOutlined />} disabled={saving} onClick={() => openUpload()}>
              上传GLB模型
            </Button>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        上传后自动解析原始尺寸并生成预览图，保存为草稿。发布后的版本可在画布模型库中选用；替换文件会创建新版本。
      </Typography.Paragraph>
      <Form
        form={searchForm}
        layout="inline"
        onFinish={(values) =>
          setQuery({
            ...query,
            pageNum: 1,
            keyword: values.keyword?.trim() || undefined,
            categoryId: values.categoryId
          })
        }
        style={{ gap: 12, marginBottom: 16 }}
      >
        <Form.Item name="keyword" label="名称或标签">
          <Input allowClear maxLength={100} placeholder="搜索模型" />
        </Form.Item>
        <Form.Item name="categoryId" label="分类">
          <Select
            allowClear
            placeholder="全部分类"
            style={{ width: 180 }}
            options={categories.map((category) => ({ value: category.id, label: category.name }))}
          />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button htmlType="submit" type="primary" loading={loading}>
              查询
            </Button>
            <Button icon={<ReloadOutlined />} disabled={loading} onClick={() => setReload((value) => value + 1)}>
              刷新
            </Button>
          </Space>
        </Form.Item>
      </Form>
      {error && <Alert showIcon type="error" message={error} style={{ marginBottom: 16 }} />}
      <Table<CanvasLibraryModel>
        rowKey="id"
        dataSource={rows}
        loading={loading}
        pagination={false}
        scroll={{ x: 1050 }}
        locale={{ emptyText: <Empty description="暂无模型，请调整筛选条件或上传GLB文件" /> }}
        columns={[
          {
            title: '模型',
            dataIndex: 'name',
            width: 220,
            render: (name: string, row) => (
              <>
                <Typography.Text strong>{name}</Typography.Text>
                <div>
                  <Typography.Text type="secondary">版本 {row.versionNo}</Typography.Text>
                </div>
              </>
            )
          },
          {
            title: '分类',
            dataIndex: 'categoryName',
            width: 130,
            render: (name, row) =>
              name || categories.find((category) => category.id === row.categoryId)?.name || '未分类'
          },
          { title: '标签', dataIndex: 'tags', width: 200, ellipsis: true },
          {
            title: '文件大小',
            dataIndex: 'fileSize',
            width: 110,
            render: (value) => `${(Number(value) / 1024 / 1024).toFixed(2)} MiB`
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (value: CanvasLibraryModel['status']) => <Tag color={colors[value]}>{labels[value] || value}</Tag>
          },
          { title: '创建时间', dataIndex: 'createTime', width: 180 },
          {
            title: '操作',
            fixed: 'right',
            hidden: !(canQuery || canEdit || canAdd || canRemove),
            width: 290,
            render: (_, row) => (
              <Space wrap size={4}>
                {canQuery && (
                  <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => void openPreview(row)}>
                    预览
                  </Button>
                )}
                {canEdit && (
                  <Button
                    size="small"
                    type="link"
                    icon={<EditOutlined />}
                    disabled={saving}
                    onClick={() => {
                      setEditing(row);
                      editForm.setFieldsValue(editInput(row));
                    }}
                  >
                    编辑
                  </Button>
                )}
                {canAdd && (
                  <Button size="small" type="link" disabled={saving} onClick={() => openUpload(row)}>
                    替换文件
                  </Button>
                )}
                {canRemove && (
                  <Button
                    size="small"
                    type="link"
                    danger
                    icon={<DeleteOutlined />}
                    disabled={saving}
                    onClick={() =>
                      Modal.confirm({
                        title: `删除“${row.name}”版本${row.versionNo}？`,
                        content: '已被项目引用的版本不能删除，可改为停用。',
                        okText: '删除',
                        okButtonProps: { danger: true },
                        cancelText: '取消',
                        onOk: () => perform('aid:canvasModel:remove', () => removeCanvasModel(editInput(row)))
                      })
                    }
                  >
                    删除
                  </Button>
                )}
              </Space>
            )
          }
        ]}
      />
      <Space wrap style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
        <Typography.Text>
          第 {query.pageNum} 页 · 本页 {rows.length} 条
        </Typography.Text>
        <Button
          disabled={loading || query.pageNum <= 1}
          onClick={() => setQuery({ ...query, pageNum: query.pageNum - 1 })}
        >
          上一页
        </Button>
        <Button
          disabled={loading || rows.length < query.pageSize || query.pageNum >= 10000}
          onClick={() => setQuery({ ...query, pageNum: query.pageNum + 1 })}
        >
          下一页
        </Button>
      </Space>
      <Modal
        title="编辑模型"
        open={!!editing && canEdit}
        confirmLoading={saving}
        onCancel={() => {
          if (!saving) setEditing(null);
        }}
        destroyOnClose
        onOk={() =>
          void perform('aid:canvasModel:edit', async (guard) => {
            const values = await editForm.validateFields();
            if (!editing) return;
            guard();
            await editCanvasModel({ ...editInput(editing), ...values });
            guard();
            setEditing(null);
          })
        }
      >
        <Form form={editForm} layout="vertical">
          <Form.Item
            name="name"
            label="模型名称"
            rules={[{ required: true, whitespace: true, message: '请填写模型名称' }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="categoryId" label="分类" rules={[{ required: true, message: '请选择分类' }]}>
            <Select
              options={categories.map((category) => ({
                value: category.id,
                label: `${category.name}${category.status === 'DISABLED' ? '（已停用）' : ''}`
              }))}
            />
          </Form.Item>
          <Form.Item name="tags" label="标签">
            <Input maxLength={500} placeholder="用逗号分隔标签" />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select options={Object.entries(labels).map(([value, label]) => ({ value, label }))} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={uploading?.modelId ? '替换GLB文件（创建新版本）' : '上传GLB模型'}
        open={!!uploading && canAdd}
        width={720}
        confirmLoading={saving}
        okText="保存草稿"
        okButtonProps={{ disabled: !file || !prepared }}
        onCancel={() => {
          if (!saving) {
            setUploading(null);
            setFile(null);
            setPrepared(null);
          }
        }}
        destroyOnClose
        onOk={() =>
          void perform('aid:canvasModel:add', async (guard) => {
            const values = await uploadForm.validateFields();
            if (!file || !prepared || !uploading) throw new Error('请等待模型预览和缩略图生成完成');
            guard();
            await uploadCanvasModel({ ...values, modelId: uploading.modelId, file, thumbnail: prepared.thumbnail });
            guard();
            setUploading(null);
            setFile(null);
            setPrepared(null);
          })
        }
      >
        <Form form={uploadForm} layout="vertical">
          <Form.Item
            name="name"
            label="模型名称"
            rules={[{ required: true, whitespace: true, message: '请填写模型名称' }]}
          >
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="categoryId" label="分类" rules={[{ required: true, message: '请选择已启用分类' }]}>
            <Select options={activeCategories.map((category) => ({ value: category.id, label: category.name }))} />
          </Form.Item>
          <Form.Item
            label="GLB文件"
            required
            extra="仅支持完整内嵌资源的GLB 2.0文件，最大20MiB。尺寸和预览图由系统自动生成。"
          >
            <Upload
              accept=".glb,model/gltf-binary"
              maxCount={1}
              disabled={saving}
              fileList={file ? [{ uid: 'model', name: file.name, status: 'done' }] : []}
              onRemove={() => {
                setFile(null);
                setPrepared(null);
                return true;
              }}
              beforeUpload={(value) => {
                if (!value.name.toLowerCase().endsWith('.glb') || value.size > MAX_MODEL_BYTES || !value.size) {
                  message.error('请选择不超过20MiB的GLB文件');
                  return Upload.LIST_IGNORE;
                }
                setPrepared(null);
                setFile(value);
                return false;
              }}
            >
              <Button icon={<UploadOutlined />}>选择GLB文件</Button>
            </Upload>
          </Form.Item>
        </Form>
        {file && <ModelPreview file={file} onPrepared={preparedCallback} />}
        {!activeCategories.length && (
          <Alert type="warning" showIcon message="暂无已启用分类，请先在分类管理中添加分类" />
        )}
      </Modal>
      <Modal
        title="模型预览"
        open={!!preview && canQuery}
        width={760}
        footer={null}
        onCancel={closePreview}
        destroyOnClose
      >
        {preview?.error ? (
          <Alert type="error" showIcon message={preview.error} />
        ) : preview?.file ? (
          <ModelPreview file={preview.file} />
        ) : (
          <Spin tip="正在读取模型">
            <div style={{ height: 100 }} />
          </Spin>
        )}
        {preview && (
          <Descriptions
            size="small"
            column={2}
            style={{ marginTop: 16 }}
            items={[
              { key: 'name', label: '名称', children: preview.row.name },
              { key: 'version', label: '版本', children: preview.row.versionNo },
              { key: 'status', label: '状态', children: labels[preview.row.status] },
              {
                key: 'category',
                label: '分类',
                children:
                  preview.row.categoryName ||
                  categories.find((category) => category.id === preview.row.categoryId)?.name
              }
            ]}
          />
        )}
      </Modal>
      <Modal
        title="模型分类"
        open={categoryOpen && canEdit}
        onCancel={() => {
          if (!saving) {
            setCategoryOpen(false);
            setCategoryEditing(null);
          }
        }}
        footer={null}
        width={680}
      >
        <Button
          icon={<PlusOutlined />}
          style={{ marginBottom: 12 }}
          disabled={saving}
          onClick={() => {
            setCategoryEditing({ id: '', name: '', status: 'ACTIVE', sortOrder: 0 });
            categoryForm.setFieldsValue({ name: '', status: 'ACTIVE', sortOrder: 0 });
          }}
        >
          新增分类
        </Button>
        <Table<CanvasModelCategory>
          size="small"
          pagination={false}
          rowKey="id"
          dataSource={categories}
          columns={[
            { title: '分类', dataIndex: 'name' },
            { title: '排序', dataIndex: 'sortOrder' },
            { title: '状态', dataIndex: 'status', render: (value) => (value === 'ACTIVE' ? '已启用' : '已停用') },
            {
              title: '操作',
              render: (_, category) => (
                <Button
                  type="link"
                  size="small"
                  disabled={saving}
                  onClick={() => {
                    setCategoryEditing(category);
                    categoryForm.setFieldsValue(category);
                  }}
                >
                  编辑
                </Button>
              )
            }
          ]}
        />
        {categoryEditing && (
          <Form
            form={categoryForm}
            layout="vertical"
            style={{ marginTop: 16 }}
            onFinish={(values) =>
              void perform('aid:canvasModel:edit', async (guard) => {
                guard();
                await saveCanvasModelCategory({ ...values, ...(categoryEditing.id ? { id: categoryEditing.id } : {}) });
                guard();
                setCategoryEditing(null);
              })
            }
          >
            <Form.Item
              name="name"
              label="分类名称"
              rules={[{ required: true, whitespace: true, message: '请填写分类名称' }]}
            >
              <Input maxLength={50} />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序" rules={[{ required: true }]}>
              <InputNumber min={0} max={10000} precision={0} />
            </Form.Item>
            <Form.Item name="status" label="状态" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'ACTIVE', label: '启用' },
                  { value: 'DISABLED', label: '停用' },
                  ...(categoryEditing.id ? [{ value: 'DELETED', label: '删除（仍被模型引用时不可删除）' }] : [])
                ]}
              />
            </Form.Item>
            <Button htmlType="submit" type="primary" loading={saving}>
              保存分类
            </Button>
          </Form>
        )}
      </Modal>
    </Card>
  );
}
