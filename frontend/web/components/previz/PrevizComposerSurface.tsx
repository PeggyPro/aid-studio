"use client";

import type { ReactNode } from "react";
import { StudioCanvasAssistantComposerSurface } from "@/components/studio/shell/StudioCanvasAssistantComposerSurface";
import "@/components/studio/shell/studio-canvas-conversation.css";
import "@/components/studio/shell/studio-canvas-assistant.css";
import { PrevizModelingMark } from "./PrevizAgentVisuals";

export function PrevizComposerSurface({ children }: {
  children: ReactNode;
}) {
  return <StudioCanvasAssistantComposerSurface awakening={false} animateResize={false}>
    {children}
  </StudioCanvasAssistantComposerSurface>;
}

export function PrevizModelingHint({ onInsert }: { onInsert: () => void }) {
  return <div className="pv-modeling-hint">
      <PrevizModelingMark animate />
      <span className="pv-modeling-hint__copy">
        <strong>从一句话开始建模</strong>
        <small>试着搭建一张圆桌和两把椅子</small>
      </span>
      <button type="button" onClick={onInsert}>填入示例</button>
    </div>;
}
