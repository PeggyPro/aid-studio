import type { AssistantSessionSnapshot } from "@/types/assistant-api";
import { assistantConversationPreparingAutomatically, assistantConversationReconciliationLabel, assistantConversationTerminal } from "@/utils/assistant/assistantConversationPresentation";

/** Never invent progress percentages or infer completion from a successful intermediate build. */
export function previzRunFeedback(snapshot?: AssistantSessionSnapshot | null, guidanceOnly = false): { busy: boolean; label?: string } {
  const run = snapshot?.activeRun;
  if (!run) return { busy: false };
  const settled: Record<string, string> = { SUCCEEDED: "本轮处理已完成", COMPLETED: "本轮处理已完成", FAILED: "本轮未完成，请查看对话中的原因", STOPPED: "本轮已停止", CANCELLED: "本轮已取消", CANCELED: "本轮已取消" };
  if (settled[run.status]) return { busy: false, label: settled[run.status] };
  if (run.stopRequested) return { busy: true, label: "正在核实停止结果…" };
  const reconciling = snapshot?.steps.findLast(item => !assistantConversationTerminal(item.status)
    && (item.status === "RECONCILING" || item.providerAcceptanceState === "UNKNOWN"));
  if (run.status === "RECONCILING" || reconciling) return { busy: true, label: assistantConversationReconciliationLabel(reconciling ?? snapshot?.steps.at(-1)) };
  if (run.status === "PAUSING") return { busy: true, label: "正在暂停…" };
  if (run.status === "STOPPING") return { busy: true, label: "正在核实停止结果…" };
  if (assistantConversationPreparingAutomatically(run)) return { busy: true, label: guidanceOnly ? "正在准备空间方案…" : "正在准备搭建场景…" };
  if (guidanceOnly && run.status === "WAITING_APPROVAL" && !run.automaticTextExecution) {
    return { busy: false, label: "发送补充要求后继续处理" };
  }
  if (["WAITING_APPROVAL", "WAITING_USER", "WAITING_INPUT", "PAUSED"].includes(run.status)) return { busy: false, label: run.status === "PAUSED" ? "已暂停，等待继续" : "等待你的确认" };
  const step = snapshot?.steps.findLast(item => ["RUNNING", "EXECUTING", "SUBMITTING", "QUEUED", "WAITING_PROVIDER"].includes(item.status));
  if (step?.status === "QUEUED") return { busy: true, label: "场景任务排队中…" };
  const label = step?.toolCode === "aid.previz.build" ? "正在构建三维场景…"
    : step?.toolCode?.includes("preview") ? "正在生成场景预览…"
    : step?.toolCode?.startsWith("aid.previz.source") ? "正在编排场景与模型…"
    : guidanceOnly ? "正在整理空间方案…" : "正在处理场景需求…";
  return { busy: true, label };
}
