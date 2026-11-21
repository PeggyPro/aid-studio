import React from 'react';
import { Alert, Form, InputNumber, Select } from 'antd';
import type { RuntimeAgentProtocolVersion } from '@/api/aid/assistantRuntimeAgent';

export default function RuntimeAgentResponsePolicyFields() {
  const form = Form.useFormInstance();
  const protocol = Form.useWatch('protocolVersion', form) as RuntimeAgentProtocolVersion | undefined;
  const legacy = protocol === 'assistant-loop/v1';
  return (
    <>
      <Form.Item name="protocolVersion" label="对话协议" rules={[{ required: true }]}>
        <Select options={[
          { value: 'assistant-loop/native-v1', label: '流式对话与原生工具调用' },
          ...(legacy ? [{ value: 'assistant-loop/v1', label: '历史结构化协议' }] : [])
        ]} onChange={(value: RuntimeAgentProtocolVersion) => {
          form.setFieldValue(['responsePolicy', 'mode'], value === 'assistant-loop/v1' ? 'STRICT_LOCAL_JSON' : 'NATIVE_TOOL_STREAM');
        }} />
      </Form.Item>
      <Form.Item name={['responsePolicy', 'mode']} label="响应格式策略" rules={[{ required: true }]}>
        <Select
          options={legacy ? [
            { value: 'STRICT_LOCAL_JSON', label: 'STRICT_LOCAL_JSON（提示词约束与本地严格校验）' },
            { value: 'NATIVE_JSON_SCHEMA', label: 'NATIVE_JSON_SCHEMA（模型原生 Schema）' }
          ] : [{ value: 'NATIVE_TOOL_STREAM', label: '自然语言流式响应与独立工具事件' }]}
        />
      </Form.Item>
      <Form.Item name={['responsePolicy', 'maxRepairAttempts']} label="自动修复调用">
        <InputNumber min={0} max={0} disabled />
      </Form.Item>
      <Alert
        type="info"
        showIcon
        message="响应策略由服务端校验"
        description={legacy
          ? '当前草稿使用历史结构化协议。切换为流式协议后，服务端会重新校验模型、工具和 Schema 配置。自动修复调用固定为 0。'
          : '对话正文逐段推送，工具参数通过独立事件解析与校验。模型必须具备已接通的流式响应及原生工具调用能力；输入输出 Schema 用于运行接口校验，不会强制聊天正文返回 JSON。自动修复调用固定为 0。'}
      />
    </>
  );
}
