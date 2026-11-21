import React from 'react';
import { Select, Space } from 'antd';
import ContractExampleEditor from './ContractExampleEditor';
import SchemaFieldEditor from './SchemaFieldEditor';

interface ContractEditorProps {
  value?: string;
  onChange?: (value: string) => void;
  direction: '输入' | '输出';
  disabled?: boolean;
  fixedFields?: boolean;
}

const inputModeKey = 'x-aid-input-mode';
const inputModes = [
  { value: 'NONE', label: '无业务输入' },
  { value: 'FREE_FORM', label: '自由文本或附件' },
  { value: 'STRUCTURED', label: '结构化参数' }
];

function inputSchema(value?: string): Record<string, unknown> | undefined {
  try {
    const parsed: unknown = JSON.parse(value?.trim() || '{}');
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : undefined;
  } catch {
    return undefined;
  }
}

function hasInputFields(schema: Record<string, unknown>): boolean {
  return Object.entries(schema).some(([key, value]) => {
    if ([inputModeKey, 'examples', '$schema', '$id', 'title', 'description'].includes(key)) return false;
    if (key === 'type') return value !== 'object';
    if (key === 'required' && Array.isArray(value)) return value.length > 0;
    if (key === 'properties' && value && typeof value === 'object') return Object.keys(value).length > 0;
    return true;
  });
}

export function skillTextOutputMode(definition: Record<string, unknown>, schemaJson?: string): 'TEXT' | 'JSON' {
  if (definition.methodOutputMode === 'TEXT' || definition.methodOutputMode === 'JSON') return definition.methodOutputMode;
  if (typeof definition.structuredOutputEnabled === 'boolean') return definition.structuredOutputEnabled ? 'JSON' : 'TEXT';
  try {
    const schema = JSON.parse(schemaJson || '{}');
    if (schema && Object.keys(schema).some(key => !['type', 'title', 'description', 'examples'].includes(key))) return 'JSON';
  } catch {
    // The contract form reports malformed values independently.
  }
  return 'TEXT';
}

export function withSkillTextOutputMode(definition: Record<string, unknown>, mode: 'TEXT' | 'JSON'): string {
  return JSON.stringify({ ...definition, methodOutputMode: mode, structuredOutputEnabled: mode === 'JSON' }, null, 2);
}

export default function ContractEditor({ value, onChange, direction, disabled, fixedFields }: ContractEditorProps) {
  const schema = direction === '输入' ? inputSchema(value) : undefined;
  const mode = typeof schema?.[inputModeKey] === 'string' ? schema[inputModeKey] : undefined;
  const hasFields = schema ? hasInputFields(schema) : false;
  const hasExamples = Array.isArray(schema?.examples) && schema.examples.length > 0;
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    {schema && <label>输入方式 <Select aria-label="输入方式"
      value={mode ?? 'LEGACY'} style={{ minWidth: 220 }} disabled={disabled || fixedFields}
      options={[
        { value: 'LEGACY', label: hasFields ? '历史配置 · 结构化参数' : '历史空配置 · 待确认' },
        ...inputModes.map(option => ({
          ...option,
          disabled: option.value === 'NONE' && (hasFields || hasExamples)
            || option.value === 'FREE_FORM' && hasFields
        }))
      ]}
      onChange={(next) => {
        const updated = { ...schema };
        if (next === 'LEGACY') delete updated[inputModeKey];
        else updated[inputModeKey] = next;
        onChange?.(JSON.stringify(updated, null, 2));
      }} /></label>}
    {mode !== 'NONE' && mode !== 'FREE_FORM' &&
      <SchemaFieldEditor value={value} onChange={onChange} disabled={disabled || fixedFields} />}
    {mode !== 'NONE' &&
      <ContractExampleEditor title={`${direction}样例`} value={value} onChange={onChange} disabled={disabled} />}
  </Space>;
}
