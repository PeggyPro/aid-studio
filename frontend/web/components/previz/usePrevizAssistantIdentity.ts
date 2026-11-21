"use client";

import { useCallback, useEffect, useState } from "react";
import { useUserStore } from "@/stores/user";
import { mainAssistantTransport } from "@/utils/business/assistant";
import { readAssistantActorIdentity } from "@/utils/assistant/assistantActorIdentity";
import { assistantErrorMessage } from "@/utils/assistant/assistantRequestCoordinator";

/** 3D预演 uses the authenticated business account and never a canvas gateway token. */
export function usePrevizAssistantIdentity() {
  const token = useUserStore((state) => state.token);
  const identity = mainAssistantTransport.identityKey();
  const [revision, setRevision] = useState(0);
  const [profile, setProfile] = useState<{ identity: string; actorId: string; error?: string } | null>(null);
  const retry = useCallback(() => setRevision((value) => value + 1), []);
  useEffect(() => {
    let active = true;
    if (!token) return;
    void readAssistantActorIdentity(mainAssistantTransport, identity, revision > 0)
      .then((actorId) => {
        if (active && identity === mainAssistantTransport.identityKey()) setProfile({ identity, actorId });
      })
      .catch((error) => {
        if (active && identity === mainAssistantTransport.identityKey())
          setProfile({ identity, actorId: "", error: assistantErrorMessage(error) });
      });
    return () => { active = false; };
  }, [token, identity, revision]);
  const current = Boolean(token) && profile?.identity === identity;
  return { identity, connected: Boolean(token), retry,
    actorId: current ? profile.actorId : "",
    error: !token ? "请登录后使用 3D预演 助手" : current ? profile.error : undefined };
}
