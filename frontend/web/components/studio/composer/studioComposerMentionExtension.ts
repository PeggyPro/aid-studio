import { Node, mergeAttributes } from '@tiptap/core'
import { ReactNodeViewRenderer } from '@tiptap/react'
import type { StudioMediaKind } from '@/types/studio'
import { StudioComposerMentionNodeView } from './StudioComposerMentionNodeView'

export type StudioComposerMentionAttrs = {
  id: string
  label: string
  previewUrl: string | null
  mediaKind: StudioMediaKind
  sourceKey: string | null
}

export type StudioComposerMentionOptions = {
  onMentionClick?: (attrs: StudioComposerMentionAttrs, rect: DOMRect) => void
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    studioMention: {
      insertStudioMention: (attrs: StudioComposerMentionAttrs) => ReturnType
    }
  }
}

export const StudioComposerMentionExtension = Node.create<StudioComposerMentionOptions>({
  name: 'studioMention',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  draggable: false,

  addOptions() {
    return {
      onMentionClick: undefined
    }
  },

  addAttributes() {
    return {
      id: { default: '' },
      label: { default: '' },
      previewUrl: { default: null },
      mediaKind: { default: 'image' },
      sourceKey: { default: null }
    }
  },

  parseHTML() {
    return [{ tag: 'span[data-studio-mention]' }]
  },

  renderHTML({ node, HTMLAttributes }) {
    return [
      'span',
      mergeAttributes(HTMLAttributes, {
        'data-studio-mention': '',
        'data-id': node.attrs.id,
        class: 'studio-composer-mention'
      }),
      `@${node.attrs.label}`
    ]
  },

  renderText({ node }) {
    return `@${node.attrs.label}`
  },

  addNodeView() {
    return ReactNodeViewRenderer(StudioComposerMentionNodeView)
  },

  addCommands() {
    return {
      insertStudioMention: (attrs) => ({ chain }) => chain()
        .insertContent({ type: this.name, attrs })
        .insertContent(' ')
        .run()
    }
  }
})
