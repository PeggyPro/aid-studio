"use client";

import { useId, useRef, useState, type ReactNode } from "react";
import { DownOutlined, LeftOutlined, RightOutlined } from "@ant-design/icons";
import { StudioCanvasTooltip } from "@/components/studio/canvas/StudioCanvasTooltip";
import { PrevizAssistantMark } from "./PrevizAgentVisuals";
import "./previz-agent-dock.css";
import { usePrevizAgentResize } from "@/hooks/previz/usePrevizAgentResize";
import { useStudioPanelPresence } from "@/hooks/studio/useStudioPanelPresence";

export function PrevizAgentDock({ expanded, mode, tools, onOpen, onCollapse, children }: {
  expanded: boolean;
  mode: "build" | "guidance";
  children: ReactNode;
  tools: ReactNode;
  onOpen: () => void;
  onCollapse: () => void;
}) {
  const toolsId = useId();
  const isModeling = mode === "build";
  const launcherRow = useRef<HTMLDivElement>(null);
  const dock = useRef<HTMLElement>(null);
  const { rendered, exiting } = useStudioPanelPresence(expanded);
  const resize = usePrevizAgentResize(dock, launcherRow, rendered);
  const [toolsCollapsed, setToolsCollapsed] = useState(false);
  const toolsLabel = toolsCollapsed ? "展开预演工具" : "收起预演工具";
  return <section ref={dock} style={resize.style} className={`pv-agent-dock${rendered ? " is-expanded" : ""}${exiting ? " is-exiting" : ""}${resize.resizing ? " is-resizing" : ""}`} aria-label="预演工具与智能体对话">
    <div id="pv-agent-content" className="pv-agent-dock__content" hidden={!rendered} inert={!expanded} aria-hidden={!expanded}>
      <StudioCanvasTooltip title="收起智能体对话">
        <button type="button" className="pv-agent-dock__collapse" aria-label="收起智能体对话" onClick={() => {
          onCollapse();
          requestAnimationFrame(() => launcherRow.current?.querySelector<HTMLButtonElement>(".pv-guidance-launch")?.focus());
        }}><DownOutlined aria-hidden /></button>
      </StudioCanvasTooltip>
      {children}
      {expanded ? <>
        <StudioCanvasTooltip title="拖动调整高度；方向键微调，Home 恢复默认">
          <div className="pv-agent-resize pv-agent-resize--top" role="separator" tabIndex={0} aria-label="调整对话框高度" aria-orientation="horizontal"
            aria-valuemin={Math.min(280, resize.bounds.height)} aria-valuemax={resize.bounds.height} aria-valuenow={resize.size.height} {...resize.bind("top")} />
        </StudioCanvasTooltip>
        {(["left", "right"] as const).map(edge => <StudioCanvasTooltip key={edge} title="拖动调整宽度；方向键微调，Home 恢复默认">
          <div className={`pv-agent-resize pv-agent-resize--${edge}`} role="separator" tabIndex={0} aria-label={`从${edge === "left" ? "左" : "右"}侧调整对话框宽度`} aria-orientation="vertical"
            aria-valuemin={Math.min(360, resize.bounds.width)} aria-valuemax={resize.bounds.width} aria-valuenow={resize.size.width} {...resize.bind(edge)} />
        </StudioCanvasTooltip>)}
        <StudioCanvasTooltip title="拖动调整宽高，双击或 Home 恢复默认">
          <div className="pv-agent-resize pv-agent-resize--corner" role="button" tabIndex={0} aria-label="调整对话框宽高" {...resize.bind("corner")}>
            <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden><path d="M3 2h7v7M6 2h4v4" fill="none" stroke="currentColor" strokeWidth="1.5" /></svg>
          </div>
        </StudioCanvasTooltip>
      </> : null}
    </div>
    <div ref={launcherRow} className="pv-agent-dock__launcher">
      <StudioCanvasTooltip title={toolsLabel}>
        <button type="button" className="pv-agent-dock__return" aria-label={toolsLabel} aria-expanded={!toolsCollapsed}
          aria-controls={toolsId} onClick={() => setToolsCollapsed(value => !value)}>
          {toolsCollapsed ? <RightOutlined aria-hidden /> : <LeftOutlined aria-hidden />}
        </button>
      </StudioCanvasTooltip>
      <div id={toolsId} className="pv-agent-dock__tools" hidden={toolsCollapsed}>{tools}</div>
      <div className="pv-agent-dock__launch-slot" hidden={expanded}>
        <button type="button" className="pv-guidance-launch" onClick={onOpen}
          aria-label="展开智能体对话" aria-expanded={expanded} aria-controls="pv-agent-content">
          <PrevizAssistantMark />
          <span className="pv-guidance-launch-copy">
            <strong>{isModeling ? "场景助手" : "空间方案"}</strong>
          </span>
          <RightOutlined className="pv-guidance-launch__chevron" aria-hidden />
        </button>
      </div>
    </div>
  </section>;
}
