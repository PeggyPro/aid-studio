'use client'

import { useState } from 'react'
import { Checkbox, Modal } from 'antd'
import './studio-library-modals.css'
import {
  STUDIO_ASSET_LIBRARY_DISCLAIMER_BODY,
  STUDIO_ASSET_LIBRARY_DISCLAIMER_TITLE
} from '@/config/studioDisclaimer'

export function StudioDisclaimerModal({
  open,
  onCancel,
  onAgree
}: {
  open: boolean
  onCancel: () => void
  onAgree: () => void
  variant?: 'free' | 'flow'
}) {
  const [accepted, setAccepted] = useState(false)
  return (
    <Modal
      open={open}
      title={STUDIO_ASSET_LIBRARY_DISCLAIMER_TITLE}
      okText="同意并继续"
      cancelText="取消"
      okButtonProps={{ disabled: !accepted }}
      width={760}
      centered
      className="studio-disclaimer-modal--canvas"
      onCancel={() => { setAccepted(false); onCancel() }}
      onOk={() => { if (accepted) { setAccepted(false); onAgree() } }}
    >
      <div className="studio-disclaimer-modal__content">
        <p>{STUDIO_ASSET_LIBRARY_DISCLAIMER_BODY}</p>
        <section><h3>1. 服务说明</h3><p>资料库用于为画布节点选择项目历史素材、上传素材和截取帧，不会自动公开或发布你的内容。</p></section>
        <section><h3>2. 内容与授权</h3><p>请确认你对所选文件、人物形象、声音和视频拥有合法使用权，并遵守适用的法律法规及平台规则。</p></section>
        <section><h3>3. 信息处理</h3><p>相关素材仅用于完成你主动发起的创作与校验流程。请勿上传无权处理的敏感或受保护信息。</p></section>
        <section><h3>4. 你的责任</h3><p>你应对上传素材、提示词及最终生成内容的使用方式负责，并在发布前完成必要的权利与内容审核。</p></section>
      </div>
      <Checkbox className="studio-disclaimer-modal__consent" checked={accepted} onChange={(event) => setAccepted(event.target.checked)}>我已阅读、理解并同意以上内容</Checkbox>
    </Modal>
  )
}
