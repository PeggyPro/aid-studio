import React from 'react';
import { Alert, Checkbox, Collapse, Divider, Form, Input, Select, Space, Switch } from 'antd';
import CapabilityEditor from './CapabilityEditor';
import StructuredValueEditor, { type ConfigValue } from './StructuredValueEditor';
import { buildCapabilityJsonObject, parseCapabilityJsonToModel } from './helpers';
import type { Model } from './types';
import type { ModelCapabilityDefinition, ModelProtocolBinding } from './modelDefinition';
import { mediaCapabilityErrors, retainUneditedInvalidMediaFields } from './mediaCapabilityValidation';

/** 保留已有中文素材约束控件，配置归属于当前能力的调用渠道。 */
export default function ModelRouteCapabilityEditor({ definition, route, modelType, onChange }: {
  definition: ModelCapabilityDefinition;
  route: ModelProtocolBinding;
  modelType: string;
  onChange: (patch: Partial<ModelProtocolBinding>) => void;
}) {
  const form = { ...definition.presentation, ...route.presentation, modelType,
    generateMode: definition.generateMode, protocol: route.protocol } as Model;
  const cap = parseCapabilityJsonToModel(JSON.stringify(route.capability || {}));
  const mediaErrors = mediaCapabilityErrors(route.capability);
  // 常用控件只修改明确编辑的字段，避免对整份能力 JSON 做无关的默认值归一化。
  const patchCapability = (patch: Record<string, ConfigValue>) => { if (!cap.parseError) onChange({ capability: { ...route.capability, ...patch } }); };
  const targetResolutions = Array.isArray(route.capability?.targetResolutionOptions)
    ? route.capability.targetResolutionOptions.filter((value): value is string => typeof value === 'string') : null;
  const selectedSizes = targetResolutions || cap.sizeOptions;
  const sizes = targetResolutions || Array.from(new Set(['480P', '720P', '1080P', '2K', '4K', ...cap.sizeOptions]));
  const customResolution = !targetResolutions && cap.allowCustomWH === true && cap.sizeOptions.length === 0;
  const durations = Array.from(new Set([5, 10, 15, ...cap.durationOptions].map(Number)))
    .filter(Number.isFinite).sort((a, b) => a - b);
  return <>
    {route.protocol === 'gemini-omni-interactions-video' && <section aria-label="Interactions 调用通道">
      <Alert type="info" showIcon message="官方能力与当前通道分别配置" description="官方完整能力保留在模型定义中。此处仅声明当前通道已确认开放的任务和传输格式，不会修改其他 Gemini 模型。" style={{ marginBottom: 16 }} />
      <Form.Item label="response_format 格式" required>
        <Select aria-label="Interactions 响应格式" value={route.capability?.interactionsResponseFormat as string}
          options={[{ value: 'array', label: 'Cloud 原生列表' }, { value: 'object', label: '兼容通道对象' }]}
          onChange={(value) => patchCapability({ interactionsResponseFormat: value })} />
      </Form.Item>
      <Form.Item label="当前通道开放的任务" required>
        <Select mode="multiple" aria-label="Interactions 开放任务" value={route.capability?.interactionsTasks as string[]}
          options={['text_to_video', 'image_to_video', 'reference_to_video', 'edit', 'extend', 'upscale'].map((value) => ({ value, label: value }))}
          onChange={(value) => patchCapability({ interactionsTasks: value })} />
      </Form.Item>
      <Form.Item label="视频交付方式" required><Select aria-label="Interactions 视频交付方式"
        value={route.capability?.interactionsDelivery as string} options={[{ value: 'uri', label: '资源地址' }, { value: 'inline', label: '内联字节' }]}
        onChange={(value) => patchCapability({ interactionsDelivery: value })} /></Form.Item>
      {route.capability?.interactionsResponseFormat === 'array' && route.capability?.interactionsDelivery === 'uri' &&
        <Form.Item label="Cloud 输出 GCS 路径" required extra="填写已授权输出桶路径；读取使用 Google Bearer 凭证，不向普通资源地址转发密钥。">
          <Input aria-label="Interactions Cloud 输出 GCS 路径" value={route.capability?.interactionsOutputGcsUri as string}
            onChange={(event) => patchCapability({ interactionsOutputGcsUri: event.target.value })} />
        </Form.Item>}
      <Space wrap>
        <label>后台异步与历史存储 <Switch aria-label="Interactions 后台异步与历史存储" checked={route.capability?.interactionsBackground === true}
          onChange={(value) => patchCapability({ interactionsBackground: value })} /></label>
        <label>通道支持幂等请求头 <Switch aria-label="Interactions 幂等请求头" checked={route.capability?.interactionsIdempotency === true}
          onChange={(value) => patchCapability({ interactionsIdempotency: value })} /></label>
      </Space>
    </section>}
    {modelType === 'audio' && typeof route.capability?.officialAudioOperation === 'string' && <section aria-label="官方音频资源授权">
      <Alert showIcon type="info" message="共享上游账号中的个人素材按用户归属校验。下列资源仅在显式开放后可供前台用户选择；后台资源管理操作仍受管理员权限控制。" style={{ marginBottom: 16 }} />
      {[
        ['officialSharedVoiceIds', 'ElevenLabs 共享音色 ID'],
        ['officialSharedDictionaryIds', 'ElevenLabs 共享发音词典 ID'],
        ['officialSharedVocalIds', 'Mureka 共享参考音色 ID'],
        ['officialSharedSeedSpeakerIds', 'Seed 共享 speaker ID']
      ].filter(([field]) => route.protocol === 'elevenlabs-audio' ? field.includes('Voice') || field.includes('Dictionary') : route.protocol === 'mureka-audio' ? field.includes('Vocal') : route.protocol === 'seed-audio' ? field.includes('Seed') : false)
        .map(([field, label]) => <Form.Item key={field} label={label} extra="填写已在当前上游账号核验、允许本站用户使用的资源标识。">
          <Select mode="tags" aria-label={label} value={Array.isArray(route.capability?.[field]) ? route.capability[field] as string[] : []}
            onChange={(values) => patchCapability({ [field]: Array.from(new Set(values.map((value) => value.trim()).filter(Boolean))) })} />
        </Form.Item>)}
    </section>}
    {mediaErrors.length > 0 && <Alert type="error" showIcon role="alert" message="媒体能力字段需要修正"
      description={<ul>{mediaErrors.map((error) => <li key={`${error.field}-${error.message}`}>
        <code>{error.field}</code>：{error.message}
      </li>)}</ul>} />}
    {(modelType === 'video' || modelType === 'image') && <section className="model-capability-quick" aria-label="常用能力配置">
      <h3>常用能力配置</h3>
      <div className="model-capability-quick__field">
        <strong>分辨率 / 规格</strong>
        {customResolution ? <small>按目标宽高设置输出尺寸，最大 {cap.maxOutputPixels
          ? `${Math.floor(cap.maxOutputPixels / 1000000)} MP` : '像素上限以模型能力为准'}；下方可调整像素限制。</small>
          : <Checkbox.Group disabled={cap.parseError} aria-label="分辨率 / 规格" options={sizes} value={selectedSizes}
            onChange={(values) => patchCapability(targetResolutions
              ? { targetResolutionOptions: values as string[] }
              : { sizeOptions: [
                  ...cap.sizeOptions.filter((value) => values.includes(value)),
                  ...values.filter((value) => !cap.sizeOptions.includes(value))
                ] })} />}
        {!customResolution && <small>按模型实际支持范围勾选；自定义尺寸和上游映射在下方完整配置中维护。</small>}
      </div>
      {route.capability?.supportsAspectRatio === true && <div className="model-capability-quick__field">
        <strong>画面比例</strong>
        <Checkbox.Group disabled={cap.parseError} aria-label="画面比例" options={Array.from(new Set(['16:9', '9:16', '1:1', '4:3', '3:4', ...cap.aspectRatioOptions]))}
          value={cap.aspectRatioOptions} onChange={(values) => patchCapability({ aspectRatioOptions: [
            ...cap.aspectRatioOptions.filter((value) => values.includes(value)),
            ...values.filter((value) => !cap.aspectRatioOptions.includes(value))
          ] })} />
      </div>}
      {modelType === 'video' && <>
        {route.capability?.supportsDuration === true && <div className="model-capability-quick__field">
          <strong>视频时长（秒）</strong>
          <Checkbox.Group disabled={cap.parseError} aria-label="视频时长（秒）" options={durations}
            value={cap.durationOptions} onChange={(values) => patchCapability({
              durationOptions: Array.from(new Set(values.map(Number))).sort((a, b) => a - b)
            })} />
        </div>}
        <Space wrap size={[24, 12]}>
          <label>音画同步 / 生成声音 <Switch disabled={cap.parseError} aria-label="音画同步 / 生成声音" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsAudio === true} onChange={(enabled) => patchCapability(enabled ? { supportsAudio: true }
              : { supportsAudio: false, defaultAudio: false, supportsBgm: false, supportsVoiceControl: false, audioTypes: [] })} /></label>
          <label>默认生成声音 <Select aria-label="默认生成声音" allowClear style={{ width: 150 }} disabled={cap.parseError || !cap.supportsAudio}
            value={cap.defaultAudio ?? undefined} placeholder="未配置"
            options={[{ value: true, label: '默认开启' }, { value: false, label: '默认关闭' }]}
            onChange={(value) => patchCapability({ defaultAudio: value ?? null })} /></label>
          <label>参考音频文件 <Switch disabled={cap.parseError} aria-label="支持参考音频文件" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsReferenceAudio === true} onChange={(enabled) => patchCapability({ supportsReferenceAudio: enabled })} /></label>
          <label>参考音色 ID <Switch disabled={cap.parseError} aria-label="支持参考音色 ID" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsVoiceId === true} onChange={(enabled) => patchCapability({ supportsVoiceId: enabled })} /></label>
          <label>视频输入 <Switch disabled={cap.parseError} aria-label="支持视频输入" checkedChildren="支持" unCheckedChildren="不支持"
            checked={cap.supportsVideoInput === true} onChange={(enabled) => patchCapability({ supportsVideoInput: enabled })} /></label>
        </Space>
      </>}
    </section>}
    <Divider orientation="left">完整能力与素材限制</Divider>
    <CapabilityEditor modelType={modelType} form={form} cap={cap}
      onCapChange={(next) => {
        onChange({ capability: retainUneditedInvalidMediaFields(route.capability,
          { ...route.capability, ...buildCapabilityJsonObject(form, next) }, cap, next) });
      }}
      onFormChange={(patch) => onChange({ presentation: { ...route.presentation, ...patch } as ModelProtocolBinding['presentation'] })} />
    <Collapse items={[{ key: 'protocol', label: '协议附加选项', children:
      <StructuredValueEditor fixedType="object" value={route.capability || {}}
        onChange={(next) => onChange({ capability: next as ModelProtocolBinding['capability'] })} /> }]} />
  </>;
}
