import React from 'react';
import { Tooltip } from 'antd';
import { displayText } from './common';

export default function Identifier({ value }: { value: unknown }) {
  const text = displayText(value);
  return (
    <Tooltip title={text === '-' ? undefined : text}>
      <span
        className="code-text canvas-code-text"
        tabIndex={text === '-' ? undefined : 0}
        aria-label={text === '-' ? undefined : text}
      >
        {text}
      </span>
    </Tooltip>
  );
}
