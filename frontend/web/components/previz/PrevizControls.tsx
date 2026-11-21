"use client";

import { useState, type ReactNode } from "react";
import { DownOutlined } from "@ant-design/icons";
import { StudioCanvasDropdownPopover } from "@/components/studio/canvas/StudioCanvasDropdownMenu";
import { StudioCanvasTooltip } from "@/components/studio/canvas/StudioCanvasTooltip";

export function BoxMark({ small = false }: { small?: boolean }) {
  return (
    <svg
      width={small ? 24 : 42}
      height={small ? 24 : 42}
      viewBox="0 0 40 40"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="m20 3 15 8.5v17L20 37 5 28.5v-17L20 3Z"
        stroke="currentColor"
        strokeWidth="2"
      />
      <path
        d="m5 11.5 15 9 15-9M20 20.5V37M12.5 7.5 28 16"
        stroke="currentColor"
        strokeWidth="2"
      />
    </svg>
  );
}
export function Tool({
  label,
  icon,
  onClick,
  disabled,
  active,
  expanded,
  controls,
  children,
}: {
  label: string;
  icon?: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  active?: boolean;
  expanded?: boolean;
  controls?: string;
  children?: ReactNode;
}) {
  return (
    <StudioCanvasTooltip title={label}>
      <button
        type="button"
        aria-label={label}
        aria-pressed={active === undefined ? undefined : active}
        aria-expanded={expanded}
        aria-controls={controls}
        disabled={disabled}
        className={`pv-tool${active ? " is-active" : ""}`}
        onClick={onClick}
      >
        {icon}
        {children}
      </button>
    </StudioCanvasTooltip>
  );
}
export function Choice({
  label,
  value,
  items,
  onChange,
  disabled = false,
  showLabel = false,
}: {
  label: string;
  value: string;
  items: { key: string; label: string; description?: string }[];
  onChange: (key: string) => void;
  disabled?: boolean;
  showLabel?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const menu = (
    <StudioCanvasDropdownPopover
      open={open}
      onOpenChange={setOpen}
      activeKey={value}
      onSelect={onChange}
      items={items}
      ariaLabel={label}
      placement="bottomLeft"
      trigger={
        <button
          type="button"
          className="pv-choice"
          aria-label={label}
          aria-haspopup="menu"
          aria-expanded={open}
          disabled={disabled}
        >
          <span>
            {items.find((i) => i.key === value)?.label ??
              (value === "add" ? "添加对象" : value)}
          </span>
          <DownOutlined />
        </button>
      }
    />
  );
  return showLabel ? <div className="pv-labeled-choice"><span>{label}</span>{menu}</div> : menu;
}
export function NumberField({
  label,
  value,
  onChange,
  min = -1000,
  max = 1000,
  step = 0.1,
}: {
  label: string;
  value: number;
  onChange: (n: number) => void;
  min?: number;
  max?: number;
  step?: number;
}) {
  return (
    <label className="pv-number">
      <span data-axis={label.match(/ [XYZ]$/)?.[0].trim()}>{label}</span>
      <input
        type="number"
        aria-label={label}
        value={Number(value.toFixed(3))}
        min={min}
        max={max}
        step={step}
        onChange={(e) => {
          if (e.target.value === "") return;
          const n = e.target.valueAsNumber;
          if (Number.isFinite(n) && n >= min && n <= max) onChange(n);
        }}
      />
    </label>
  );
}
