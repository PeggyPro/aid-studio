import type { ModelCapabilityDefinition } from './modelDefinition';

export interface MediaCapabilityError { field: string; message: string; path: string[] }
const prefixes = ['referenceImage', 'referenceVideo', 'referenceAudio'];
const bounds = ['DurationSeconds', 'DimensionPixels', 'Pixels', 'Width', 'Height', 'AspectRatio', 'Fps'];
const suffixes = ['MinDurationSeconds', 'MaxDurationSeconds', 'MaxTotalDurationSeconds', 'MaxFileSizeMb',
  'MinDimensionPixels', 'MaxDimensionPixels', 'MinPixels', 'MaxPixels', 'MinWidth', 'MaxWidth',
  'MinHeight', 'MaxHeight', 'MinAspectRatio', 'MaxAspectRatio', 'MinFps', 'MaxFps'];
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
// 与服务端历史兼容归一保持一致；空串、布尔及其他非数字值仍明确拒绝。
const numeric = (value: unknown): number | null => {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && /^[+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)$/.test(value.trim())) {
    const parsed = Number(value.trim());
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
};

/** 检查原始草稿，避免图形控件把非法值转换成未配置后才保存。null 表示明确清空。 */
export function mediaCapabilityErrors(raw: unknown): MediaCapabilityError[] {
  const errors: MediaCapabilityError[] = [];
  const add = (field: string, message: string, path = [field]) => errors.push({ field, message, path });
  if (raw == null) return errors;
  if (!object(raw)) return [{ field: 'capability', message: '必须是对象', path: [] }];
  const check = (capability: Record<string, unknown>, prefix: string[] = []) => {
    const addField = (field: string, message: string) => add([...prefix, field].join('.'), message, [...prefix, field]);
    for (const kind of ['Images', 'Videos', 'Audios']) {
      for (const [field, minimum] of [[`minReference${kind}`, 0], [`maxReference${kind}`, -1]] as const) {
        const value = capability[field];
        if (value == null) continue;
        const number = numeric(value);
        if (number == null || !Number.isInteger(number) || number < minimum || number > 2147483647)
          addField(field, minimum === -1 ? '必须是 -1 或非负整数' : '必须是非负整数');
      }
      const min = numeric(capability[`minReference${kind}`]);
      const max = numeric(capability[`maxReference${kind}`]);
      if (min != null && max != null && max >= 0 && min > max)
        addField(`minReference${kind}`, '不能大于最大值');
    }
    const maxMaterials = capability.maxReferenceMaterials;
    if (maxMaterials != null) {
      const number = numeric(maxMaterials);
      if (number == null || !Number.isInteger(number) || number < -1 || number > 2147483647)
        addField('maxReferenceMaterials', '必须是 -1 或非负整数');
    }
    for (const prefix of prefixes) {
      for (const suffix of suffixes) {
        const field = prefix + suffix;
        const value = capability[field];
        if (value != null && (numeric(value) == null || numeric(value)! < 0))
          addField(field, '必须是非负数字');
      }
      for (const suffix of bounds) {
        const min = numeric(capability[`${prefix}Min${suffix}`]);
        const max = numeric(capability[`${prefix}Max${suffix}`]);
        if (min != null && max != null && max > 0 && min > max)
          addField(`${prefix}Min${suffix}`, '不能大于最大值');
      }
      const formats = capability[`${prefix}Formats`];
      if (formats != null && (!Array.isArray(formats) || formats.some((format) =>
        typeof format !== 'string' || !format.trim() || (format === '*' && formats.length !== 1))))
        addField(`${prefix}Formats`, '必须是非空格式的数组，通配符必须单独配置');
    }
    for (const field of ['maxInputMediaTotalFileSizeMb', 'maxInputOutputVideoDurationSeconds']) {
      const value = capability[field];
      if (value != null && (numeric(value) == null || numeric(value)! < 0)) addField(field, '必须是非负数字');
    }
  };
  check(raw);
  if (raw.sceneRules != null) {
    if (!object(raw.sceneRules)) add('sceneRules', '必须是对象');
    else for (const [scene, value] of Object.entries(raw.sceneRules)) {
      if (value == null) continue;
      if (!object(value)) add(`sceneRules.${scene}`, '必须是对象', ['sceneRules', scene]);
      else check(value, ['sceneRules', scene]);
    }
  }
  return errors;
}

/** 图形编辑器只能替换明确编辑过的字段，非法原值留在草稿里等待修正。 */
export function retainUneditedInvalidMediaFields<T extends Record<string, any>>(
  raw: Record<string, unknown> | undefined, built: T, previous: unknown, next: unknown
): T {
  const result = JSON.parse(JSON.stringify(built));
  const read = (root: unknown, path: string[]): unknown => path.reduce<unknown>((value, key) =>
    object(value) ? value[key] : undefined, root);
  for (const error of mediaCapabilityErrors(raw)) {
    if (!error.path.length || JSON.stringify(read(previous, error.path)) !== JSON.stringify(read(next, error.path))) continue;
    let target = result;
    for (const segment of error.path.slice(0, -1)) {
      const nested = object(target[segment]) ? { ...target[segment] } : {};
      Object.defineProperty(target, segment, { value: nested, enumerable: true, configurable: true, writable: true });
      target = nested;
    }
    Object.defineProperty(target, error.path[error.path.length - 1], {
      value: read(raw, error.path), enumerable: true, configurable: true, writable: true
    });
  }
  return result;
}

export function modelMediaCapabilityErrors(definitions: ModelCapabilityDefinition[]): string[] {
  return definitions.flatMap((definition, ci) => definition.bindings.flatMap((binding, ri) =>
    mediaCapabilityErrors(binding.capability).map((error) =>
      `capabilities[${ci}].bindings[${ri}].capability.${error.field}：${error.message}`)));
}
