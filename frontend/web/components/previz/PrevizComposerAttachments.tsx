"use client";

import { useEffect, useRef, useState } from "react";
import { PlusOutlined, FileImageOutlined, FileTextOutlined } from "@ant-design/icons";
import { StudioCanvasDropdownPopover } from "@/components/studio/canvas/StudioCanvasDropdownMenu";
import { StudioCanvasTooltip } from "@/components/studio/canvas/StudioCanvasTooltip";
import type { AssistantContextRef } from "@/types/assistant-api";
import { createPrevizWorkspaceApi } from "@/utils/previz/remoteWorkspace";
import { preparePrevizReferenceImage } from "@/utils/previz/referenceImage";
import { assistantErrorMessage } from "@/utils/assistant/assistantRequestCoordinator";

type ImageRef = Extract<AssistantContextRef, { kind: "PREVIZ_IMAGE" }>;
export function PrevizComposerAttachments({ projectId, disabled, images, onChange, onBusyChange, openDocuments }: {
  projectId: string; disabled: boolean; images: ImageRef[]; onChange: (images: ImageRef[]) => void;
  onBusyChange: (busy: boolean) => void; openDocuments: () => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const lock = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const [busy, setBusy] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [error, setError] = useState("");
  const upload = async (file?: File) => {
    if (!file || lock.current || disabled) return;
    lock.current = true; setBusy(true); onBusyChange(true); setError("");
    const api = createPrevizWorkspaceApi();
    try {
      if (images.length >= 4) throw new Error("每次最多引用4张图片");
      const contentBase64=await preparePrevizReferenceImage(file);
      if (!alive.current) return;
      const saved = await api.saveReferenceImage(projectId, contentBase64);
      if (!alive.current) return;
      if (!images.some(image => image.objectId === saved.objectId)) onChange([...images, {
        kind: "PREVIZ_IMAGE", previzProjectId: projectId, objectId: saved.objectId, contentHash: saved.contentHash,
        label: file.name.slice(0, 100),
      }]);
    } catch (failure) { if (alive.current) setError(assistantErrorMessage(failure)); }
    finally { lock.current = false; if (alive.current) { setBusy(false); onBusyChange(false); if (input.current) input.current.value = ""; } }
  };
  return <>
    <input ref={input} type="file" aria-label="选择三维参考图片" accept="image/png,image/jpeg,image/webp" hidden
      onChange={event => void upload(event.target.files?.[0])} />
    {(images.length > 0 || busy || error) ? <div className="pv-composer-references">
      {busy ? <span role="status">正在保存参考图…</span> : null}
      {images.map(image => <button key={image.objectId} type="button" disabled={disabled || busy}
        aria-label={`移除参考图 ${image.label}`} onClick={() => onChange(images.filter(item => item.objectId !== image.objectId))}>
        <FileImageOutlined /> <span>{image.label}</span> ×
      </button>)}
      {error ? <p role="alert">{error}</p> : null}
    </div> : null}
    <div className="pv-composer-attachment-trigger">
      <StudioCanvasDropdownPopover open={menuOpen} onOpenChange={setMenuOpen} placement="topLeft"
        ariaLabel="添加对话参考"
        items={[
          { key: 'image', label: '添加参考图', icon: <FileImageOutlined />, disabled: disabled || busy || images.length >= 4,
            disabledHint: images.length >= 4 ? '每次最多引用4张图片，请先移除一张' : undefined },
          { key: 'document', label: '添加参考文档', icon: <FileTextOutlined />, disabled: disabled || busy }
        ]}
        onSelect={key => { if (key === 'image') input.current?.click(); else openDocuments(); }}
        trigger={<StudioCanvasTooltip title="添加参考图或参考文档"><button type="button" className="pv-composer-add"
          aria-label="添加对话参考" aria-haspopup="menu" aria-expanded={menuOpen} disabled={disabled || busy}><PlusOutlined /></button></StudioCanvasTooltip>} />
    </div>
  </>;
}
