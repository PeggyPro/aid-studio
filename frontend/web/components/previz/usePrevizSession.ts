"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type {
  PrevizCommand,
  PrevizWorkingCopyStore,
  PrevizProject,
  PrevizScene,
} from "@/types/previz";
import { applyCommands, copy, uid } from "@/utils/previz/core";
import { PrevizConflictError } from "@/utils/previz/gateway";

let fallbackSessionId: string | undefined;
function editorSessionId(): string {
  try {
    const key = "aid-previz-editor-session";
    const existing = sessionStorage.getItem(key);
    if (existing) return existing;
    const id = uid();
    sessionStorage.setItem(key, id);
    return id;
  } catch {
    return (fallbackSessionId ??= uid());
  }
}

export function usePrevizSession(
  initial: PrevizProject,
  gateway: PrevizWorkingCopyStore,
  owner: string,
) {
  const [project, setProject] = useState(initial),
    ref = useRef(initial);
  const [saveState, setSaveState] = useState("已保存在本机"),
    [error, setError] = useState(""),
    [conflict, setConflict] = useState(false);
  const [history, setHistory] = useState<{
      past: PrevizScene[];
      future: PrevizScene[];
    }>({ past: [], future: [] }),
    historyRef = useRef(history);
  const generation = useRef(0),
    savedGeneration = useRef(0),
    revision = useRef(initial.revision),
    inFlight = useRef<Promise<void> | null>(null),
    conflicted = useRef(false),
    mounted = useRef(true),
    recoveryLoaded = useRef(false),
    recoveryDiscarded = useRef(false);
  const [sessionId] = useState(editorSessionId);
  const key = `aid-previz-recovery:${owner}:${initial.id}:${sessionId}`;
  const recoveryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const writeRecovery = useCallback(() => {
    if (recoveryDiscarded.current) return;
    if (recoveryTimer.current) clearTimeout(recoveryTimer.current);
    recoveryTimer.current = null;
    if (generation.current === savedGeneration.current) return;
    try {
      localStorage.setItem(
        key,
        JSON.stringify({
          project: ref.current,
          baseRevision: revision.current,
        }),
      );
    } catch {
      if (mounted.current)
        setError("恢复草稿空间不足，正在尝试工程库保存；请导出工程包备份");
    }
  }, [key]);
  const mutate = useCallback(
    (update: (p: PrevizProject) => PrevizProject) => {
      const next = update(ref.current);
      ref.current = next;
      generation.current++;
      setProject(next);
      setSaveState("待保存到本机");
      // Coalesce slider / input updates; serialization must not block every gesture.
      if (recoveryTimer.current) clearTimeout(recoveryTimer.current);
      recoveryTimer.current = setTimeout(writeRecovery, 100);
    },
    [writeRecovery],
  );
  const execute = useCallback(
    (commands: PrevizCommand[],patch:Partial<Omit<PrevizProject,'scene'>>={}) => {
      const next = applyCommands(ref.current.scene, commands);
      const h = {
        past: [...historyRef.current.past, copy(ref.current.scene)].slice(-50),
        future: [],
      };
      historyRef.current = h;
      setHistory(h);
      mutate((p) => ({ ...p,...patch, scene: next }));
    },
    [mutate],
  );
  const travel = useCallback(
    (direction: "undo" | "redo") => {
      const h = historyRef.current,
        source = direction === "undo" ? h.past : h.future;
      if (!source.length) return;
      const next = source[source.length - 1];
      const updated =
        direction === "undo"
          ? {
              past: h.past.slice(0, -1),
              future: [...h.future, copy(ref.current.scene)],
            }
          : {
              past: [...h.past, copy(ref.current.scene)],
              future: h.future.slice(0, -1),
            };
      historyRef.current = updated;
      setHistory(updated);
      mutate((p) => ({ ...p, scene: copy(next) }));
    },
    [mutate],
  );
  const flush = useCallback(async () => {
    if (recoveryDiscarded.current) return;
    while (inFlight.current) await inFlight.current;
    if (recoveryDiscarded.current) return;
    if (conflicted.current) throw new PrevizConflictError();
    if (generation.current === savedGeneration.current) return;
    const save = async () => {
      const atStart = generation.current,
        snapshot = copy(ref.current);
      if (mounted.current) setSaveState("正在保存到本机");
      try {
        const saved = await gateway.save({
          project: snapshot,
          expectedRevision: revision.current,
          operationId: uid(),
          summary: "场景编辑",
        });
        revision.current = saved.revision;
        savedGeneration.current = atStart;
        ref.current = {
          ...ref.current,
          revision: saved.revision,
          versionId: saved.versionId,
          versions: saved.versions,
          updatedAt: saved.updatedAt,
        };
        if (generation.current === atStart) localStorage.removeItem(key);
        else {
          try {
            localStorage.setItem(
              key,
              JSON.stringify({
                project: ref.current,
                baseRevision: revision.current,
              }),
            );
          } catch {
            /* The latest edit remains in memory and is saved next. */
          }
        }
        if (mounted.current) {
          setProject(ref.current);
          setSaveState(
            generation.current === atStart ? "已保存在本机" : "待保存到本机",
          );
        }
      } catch (e) {
        if (e instanceof PrevizConflictError) {
          conflicted.current = true;
          if (mounted.current) setConflict(true);
        }
        if (mounted.current) {
          setSaveState("保存失败");
          setError(e instanceof Error ? e.message : "保存失败");
        }
        throw e;
      }
    };
    const pending = save();
    inFlight.current = pending;
    try {
      await pending;
    } finally {
      inFlight.current = null;
    }
  }, [gateway, key]);
  const discardRecovery = useCallback(async () => {
    while (inFlight.current) await inFlight.current.catch(() => undefined);
    recoveryDiscarded.current = true;
    if (recoveryTimer.current) clearTimeout(recoveryTimer.current);
    recoveryTimer.current = null;
    localStorage.removeItem(key);
  }, [key]);
  useEffect(() => {
    mounted.current = true;
    if (!recoveryLoaded.current) {
      recoveryLoaded.current = true;
      let recovery: { project: PrevizProject; baseRevision: number } | undefined;
      try {
        const stored = localStorage.getItem(key);
        if (stored) recovery = JSON.parse(stored);
      } catch {
        setError("恢复草稿损坏，已保留工程库中的版本");
      }
      if (recovery && recovery.project.id === initial.id) {
        try {
          applyCommands(recovery.project.scene, []);
          mutate(() => recovery!.project);
          if (recovery.baseRevision !== initial.revision) {
            conflicted.current = true;
            setConflict(true);
            setError(
              "检测到未保存草稿及其他窗口的新版本。草稿已保留，请另存副本或重新载入。",
            );
          } else setError("已恢复上次未保存的本地草稿");
        } catch {
          setError("恢复草稿无法通过校验，已保留工程库中的版本");
        }
      }
      }
    const beforeUnload = (e: BeforeUnloadEvent) => {
      if (generation.current !== savedGeneration.current) {
        writeRecovery();
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", beforeUnload);
    return () => {
      writeRecovery();
      mounted.current = false;
      window.removeEventListener("beforeunload", beforeUnload);
      void flush().catch(() => undefined);
    };
    // Session identity is fixed by the editor key. Recovery runs once.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    if (generation.current === savedGeneration.current || conflict) return;
    const timer = setTimeout(() => {
      void flush().catch(() => undefined);
    }, 350);
    return () => clearTimeout(timer);
  }, [project, flush, conflict]);
  return {
    project,
    ref,
    generation,
    mutate,
    replaceWorkspace: (patch: Partial<PrevizProject>) => {
      const empty={past:[] as PrevizScene[],future:[] as PrevizScene[]};historyRef.current=empty;setHistory(empty);
      mutate(p=>({...p,...patch}));
    },
    execute,
    undo: () => travel("undo"),
    redo: () => travel("redo"),
    canUndo: !!history.past.length,
    canRedo: !!history.future.length,
    flush,
    saveState,
    error,
    setError,
    conflict,
    recoveryKey: key,
    discardRecovery,
    sessionId,
  };
}
