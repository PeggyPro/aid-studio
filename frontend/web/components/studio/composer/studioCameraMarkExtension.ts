import { Node, mergeAttributes } from '@tiptap/core'
import { ReactNodeViewRenderer } from '@tiptap/react'
import type { StudioCameraMark } from '@/utils/studio/studioCameraMark'
import { StudioCameraMarkNodeView } from './StudioCameraMarkNodeView'

export type StudioCameraMarkOptions = {
  onCameraMarkClick?: (mark: StudioCameraMark) => void
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    studioCameraMark: {
      insertStudioCameraMark: (mark: StudioCameraMark) => ReturnType
      replaceStudioCameraMark: (id: string, mark: StudioCameraMark) => ReturnType
    }
  }
}

export const StudioCameraMarkExtension = Node.create<StudioCameraMarkOptions>({
  name: 'studioCameraMark',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  draggable: false,

  addOptions() {
    return { onCameraMarkClick: undefined }
  },

  addAttributes() {
    return {
      id: { default: '' },
      kind: { default: 'motion' },
      optionKey: { default: '' },
      label: { default: '' },
      description: { default: undefined }
    }
  },

  parseHTML() {
    return [{ tag: 'span[data-studio-camera-mark]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, {
      'data-studio-camera-mark': '',
      'data-id': node.attrs.id,
      class: 'studio-composer-mention'
    }), node.attrs.label]
  },

  renderText({ node }) {
    return String(node.attrs.label || '')
  },

  addNodeView() {
    return ReactNodeViewRenderer(StudioCameraMarkNodeView)
  },

  addCommands() {
    return {
      insertStudioCameraMark: (mark) => ({ chain }) => chain()
        .insertContent({ type: this.name, attrs: mark })
        .insertContent(' ')
        .run(),
      replaceStudioCameraMark: (id, mark) => ({ state, dispatch }) => {
        let pos: number | null = null
        state.doc.descendants((node, nodePos) => {
          if (pos != null) return false
          if (node.type.name === this.name && node.attrs.id === id) {
            pos = nodePos
            return false
          }
          return true
        })
        if (pos == null) return false
        if (dispatch) dispatch(state.tr.setNodeMarkup(pos, undefined, mark))
        return true
      }
    }
  }
})
