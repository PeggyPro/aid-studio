import { ArrowUpOutlined } from "@ant-design/icons";
import "./previz-agent-visuals.css";

/** The assistant launcher is a persistent affordance, independent of build/task history. */
export function PrevizAssistantMark() {
  return <svg className="pv-assistant-mark" viewBox="0 0 48 48" aria-hidden="true" focusable="false">
    <g className="pv-assistant-mark__orbit">
      <circle className="pv-assistant-mark__ring" cx="24" cy="24" r="19" strokeDasharray="28 16 12 64" />
      <circle className="pv-assistant-mark__satellite" cx="43" cy="24" r="2.6" />
      <circle className="pv-assistant-mark__satellite is-secondary" cx="10.5" cy="10.5" r="1.8" />
    </g>
    <g className="pv-assistant-mark__face">
      <path className="pv-assistant-mark__antenna" d="M24 12v4" />
      <circle className="pv-assistant-mark__satellite" cx="24" cy="10" r="2" />
      <rect className="pv-assistant-mark__head" x="12.5" y="16" width="23" height="18" rx="7" />
      <circle className="pv-assistant-mark__eye" cx="19.5" cy="23.5" r="1.8" />
      <circle className="pv-assistant-mark__eye" cx="28.5" cy="23.5" r="1.8" />
      <path className="pv-assistant-mark__smile" d="M20 28.5q4 3 8 0" />
    </g>
  </svg>;
}

export function PrevizModelingMark({ animate = false }: { animate?: boolean }) {
  return <svg className="pv-modeling-mark" data-build-animation={animate ? "" : undefined}
    viewBox="0 0 48 48" aria-hidden="true" focusable="false">
    <path className="pv-modeling-mark__grid" d="M8 34 24 43 40 34 24 25Z M16 38.5 32 29.5 M16 29.5 32 38.5" />
    <g className="pv-modeling-mark__faces">
      <path className="pv-modeling-mark__top" d="M11 15 24 8 37 15 24 22Z" />
      <path className="pv-modeling-mark__left" d="M11 15 24 22V37L11 30Z" />
      <path className="pv-modeling-mark__right" d="M24 22 37 15V30L24 37Z" />
    </g>
    <path className="pv-modeling-mark__outline" pathLength="1" d="M11 15 24 8 37 15V30L24 37 11 30Z M11 15 24 22 37 15 M24 22V37" />
    <path className="pv-modeling-mark__scan" d="M11 16 24 23 37 16" />
  </svg>;
}

export function PrevizAgentActionGlyph({ stop = false }: { stop?: boolean }) {
  return <span className={`pv-agent-action${stop ? " pv-agent-action--stop" : ""}`} aria-hidden="true">
    {stop ? <span className="pv-agent-action__stop-mark" /> : <ArrowUpOutlined />}
  </span>;
}
