"use client";

import { StudioWorkspaceModalProvider } from "@/components/studio/shell/StudioWorkspaceModalProvider";
import type { ReactNode } from "react";

/** Context reaches portaled dialogs, including the shared assistant's reference documents. */
export function PrevizModalProvider({ children }: { children: ReactNode }) {
  return <StudioWorkspaceModalProvider rootClassName="pv-modal create-flow-modal">
    {children}
  </StudioWorkspaceModalProvider>;
}
