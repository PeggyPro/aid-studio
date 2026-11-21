"use client";

import { useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type MouseEvent, type PointerEvent, type RefObject } from "react";

export const PREVIZ_AGENT_DEFAULT_SIZE = { width: 510, height: 504 };
type Size = typeof PREVIZ_AGENT_DEFAULT_SIZE;
type Edge = "left" | "right" | "top" | "corner";
export type PrevizAgentBounds = { width: number; height: number };
export function clampPrevizAgentSize(size: Size, bounds: PrevizAgentBounds): Size {
  return {
    width: Math.round(Math.max(Math.min(360, bounds.width), Math.min(bounds.width, size.width))),
    height: Math.round(Math.max(Math.min(280, bounds.height), Math.min(bounds.height, size.height))),
  };
}

/** Bottom-centred sizing belongs to the dock, independent of messages and scene state. */
export function usePrevizAgentResize(root: RefObject<HTMLElement | null>, launcher: RefObject<HTMLDivElement | null>, expanded: boolean) {
  const preferred = useRef(PREVIZ_AGENT_DEFAULT_SIZE);
  const [requested, setRequested] = useState(PREVIZ_AGENT_DEFAULT_SIZE);
  const [size, setSize] = useState(PREVIZ_AGENT_DEFAULT_SIZE);
  const [bounds, setBounds] = useState({ width: 1200, height: 900 });
  const [launcherHeight, setLauncherHeight] = useState(56);
  const [resizing, setResizing] = useState(false);
  const drag = useRef<{ id: number; element: HTMLElement; x: number; y: number; size: Size; edge: Edge } | null>(null);
  const frame = useRef(0);
  const pending = useRef<Size | null>(null);

  const readBounds = () => {
    const element = root.current, host = element?.parentElement;
    if (!element || !host) return { width: 1200, height: 900 };
    const toolsHeight = launcher.current?.getBoundingClientRect().height ?? 56;
    const bottom = parseFloat(getComputedStyle(element).bottom) || 14;
    setLauncherHeight(toolsHeight);
    return { width: Math.max(1, host.clientWidth - 16), height: Math.max(1, host.clientHeight - bottom - toolsHeight - 16) };
  };
  const apply = (next: Size, remember: boolean) => {
    const available = readBounds();
    const value = clampPrevizAgentSize(next, available);
    if (remember) {
      preferred.current = value;
      setRequested(old => old.width === value.width && old.height === value.height ? old : value);
    }
    setBounds(old => old.width === available.width && old.height === available.height ? old : available);
    setSize(old => old.width === value.width && old.height === value.height ? old : value);
  };
  useLayoutEffect(() => {
    if (!expanded || !root.current?.parentElement) return;
    const observer = new ResizeObserver(() => apply(preferred.current, false));
    observer.observe(root.current.parentElement);
    observer.observe(root.current);
    if (launcher.current) observer.observe(launcher.current);
    apply(preferred.current, false);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame.current);
      frame.current = 0;
      pending.current = null;
      const active = drag.current;
      drag.current = null;
      setResizing(false);
      if (active?.element.hasPointerCapture(active.id)) active.element.releasePointerCapture(active.id);
    };
    // The observer reads live DOM bounds and refs; no message/render state is captured.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded, root, launcher]);

  const finish = (cancel = false) => {
    const active = drag.current;
    if (!active) return;
    cancelAnimationFrame(frame.current); frame.current = 0;
    if (cancel) apply(active.size, true);
    else if (pending.current) apply(pending.current, true);
    pending.current = null; drag.current = null; setResizing(false);
    if (active.element.hasPointerCapture(active.id)) active.element.releasePointerCapture(active.id);
  };
  const reset = () => apply(PREVIZ_AGENT_DEFAULT_SIZE, true);
  const bind = (edge: Edge) => ({
    onPointerDown: (event: PointerEvent<HTMLElement>) => {
      if (event.button !== 0 || drag.current) return;
      event.preventDefault(); event.stopPropagation(); event.currentTarget.focus({ preventScroll: true });
      event.currentTarget.setPointerCapture(event.pointerId);
      drag.current = { id: event.pointerId, element: event.currentTarget, x: event.clientX, y: event.clientY, size, edge };
      setResizing(true);
    },
    onPointerMove: (event: PointerEvent<HTMLElement>) => {
      const active = drag.current;
      if (!active || active.id !== event.pointerId) return;
      event.preventDefault(); event.stopPropagation();
      pending.current = {
        width: active.size.width + (edge === "top" ? 0 : (event.clientX - active.x) * (edge === "left" ? -2 : 2)),
        height: active.size.height + (edge === "top" || edge === "corner" ? active.y - event.clientY : 0),
      };
      if (!frame.current) frame.current = requestAnimationFrame(() => {
        frame.current = 0;
        if (pending.current) { apply(pending.current, true); pending.current = null; }
      });
    },
    onPointerUp: (event: PointerEvent<HTMLElement>) => { if (drag.current?.id === event.pointerId) { event.stopPropagation(); finish(); } },
    onPointerCancel: () => finish(true),
    onLostPointerCapture: () => finish(),
    onDoubleClick: (event: MouseEvent<HTMLElement>) => { event.preventDefault(); event.stopPropagation(); reset(); },
    onKeyDown: (event: KeyboardEvent<HTMLElement>) => {
      if (event.key === "Escape" && drag.current) { event.preventDefault(); event.stopPropagation(); finish(true); return; }
      if (event.key === "Home" || edge === "corner" && (event.key === "Enter" || event.key === " ")) { event.preventDefault(); event.stopPropagation(); reset(); return; }
      const step = event.shiftKey ? 40 : 16;
      const width = edge !== "top" ? (event.key === "ArrowRight" ? step : event.key === "ArrowLeft" ? -step : 0) * (edge === "left" ? -1 : 1) : 0;
      const height = edge === "top" || edge === "corner" ? event.key === "ArrowUp" ? step : event.key === "ArrowDown" ? -step : 0 : 0;
      if (!width && !height) return;
      event.preventDefault(); event.stopPropagation(); apply({ width: size.width + width, height: size.height + height }, true);
    },
  });
  return { size, bounds, resizing, bind, style: {
    // Keep the requested size in CSS: a timeline offset can change without a host resize.
    // CSS bounds shrink the painted panel and restore it immediately when space returns.
    "--pv-agent-width": `${requested.width}px`, "--pv-agent-height": `${requested.height}px`, "--pv-agent-launcher-height": `${launcherHeight}px`,
  } as CSSProperties };
}
