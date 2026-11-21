"use client";

import { useRef, type ReactNode } from "react";
import AgentConversationViewport, { type AgentConversationViewportHandle } from "@/components/common/agent-conversation/AgentConversationViewport";
import { StudioCanvasAssistantLatest } from "@/components/studio/shell/StudioCanvasAssistantLatest";
import "@/components/studio/shell/studio-canvas-assistant-interaction.css";
import { PrevizComposerSurface, PrevizModelingHint } from "./PrevizComposerSurface";
import "./previz-assistant.css";

/** Keeps the live conversation mounted while only changing its presentation. */
export function PrevizAssistantDock({
  open,
  scopeKey,
  updateToken,
  initialLoading,
  thread,
  composer,
  onInsertModelingExample,
  status,
  busy = false,
}: {
  open: boolean;
  scopeKey: string;
  updateToken: string;
  initialLoading: boolean;
  thread: ReactNode;
  composer: ReactNode;
  onInsertModelingExample?: () => void;
  status?: string;
  busy?: boolean;
}) {
  const history = useRef<AgentConversationViewportHandle>(null);
  return (
    <section
      className="pv-conversation-dock canvas-assistant-panel"
      aria-label="3D预演 对话"
      onPointerDown={(event) => event.stopPropagation()}
      onWheel={(event) => event.stopPropagation()}
      onKeyDown={(event) => {
        // Portaled dialogs keep their own Escape/focus lifecycle instead of closing the dock behind them.
        if (!event.currentTarget.contains(event.target as Node)) return;
        event.stopPropagation();
      }}
    >
      {status ? (
        <div className="pv-conversation-dock__status" role="status" aria-live="polite">
          <span className={busy ? "pv-pulse-dot" : "pv-dot"} />
          {status}
        </div>
      ) : null}
      <AgentConversationViewport ref={history} open={open} scopeKey={scopeKey} updateToken={updateToken} initialLoading={initialLoading}
        className="pv-conversation-dock__history" followContentResize revealHistory={false}>
        {onInsertModelingExample ? <PrevizModelingHint onInsert={onInsertModelingExample} /> : null}
        {thread}
      </AgentConversationViewport>
      <StudioCanvasAssistantLatest sessionId={scopeKey} onJumpToLatest={() => {
        const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        history.current?.scrollToLatest(reduceMotion ? "auto" : "smooth");
      }} />
      <div className="pv-conversation-dock__composer">
        <PrevizComposerSurface>{composer}</PrevizComposerSurface>
      </div>
    </section>
  );
}
