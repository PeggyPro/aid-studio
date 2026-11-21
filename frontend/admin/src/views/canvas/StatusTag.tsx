import React from 'react';
import { Tag } from 'antd';
import { displayText } from './common';

export default function StatusTag({ status }: { status: unknown }) {
  const value = String(status ?? '').toUpperCase();
  const active = ['0', 'ACTIVE', 'ONLINE', 'ENABLED', 'VALID'].includes(value);
  const disabled = ['1', 'DISABLED', 'OFFLINE', 'REVOKED', 'EXPIRED'].includes(value);
  return <Tag color={active ? 'green' : disabled ? 'default' : 'blue'}>{displayText(status)}</Tag>;
}
