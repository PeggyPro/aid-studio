import type { TourDefinition } from './types'

export const ONBOARDING_TOURS: TourDefinition[] = [
  // The workspace and the embedded stage own their rendering and readiness.
  { id: 'studio-free', version: 1, type: 'spotlight', trigger: { requiresLogin: true } },
  { id: 'studio-previz', version: 1, type: 'spotlight', trigger: { requiresLogin: true } },
  {
    id: 'page-case-detail',
    version: 1,
    type: 'spotlight',
    trigger: { route: /^\/case\//, priority: 2, requiresLogin: false },
    spotlightSteps: [
      {
        id: 'case-1',
        element: 'case-video-player',
        title: '观看案例',
        description: '左侧播放区预览成片效果。',
        side: 'right'
      },
      {
        id: 'case-2',
        element: 'case-info-panel',
        title: '案例信息',
        description: '右侧查看剧情、类型与创作说明。',
        side: 'left'
      }
    ]
  },
  {
    id: 'create-first-step',
    version: 1,
    type: 'spotlight',
    trigger: { modalKey: 'create-first-step', priority: 0, requiresLogin: true },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'cfs-1',
        element: 'create-modal-title',
        title: '作品名称',
        description: '填写项目标题，便于后续管理。',
        side: 'bottom'
      },
      {
        id: 'cfs-2',
        element: 'create-modal-type',
        title: '作品类型',
        description: '选择电影或剧集，决定后续流程。',
        side: 'bottom'
      },
      {
        id: 'cfs-3',
        element: 'create-modal-confirm',
        title: '确认创建',
        description: '点确认进入创作流程。',
        side: 'top',
        advanceOn: 'click-target'
      }
    ]
  },
  {
    id: 'create-global-setting',
    version: 1,
    type: 'spotlight',
    trigger: { route: /^\/create\/global-setting/, priority: 1, requiresLogin: true },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'cgs-1',
        element: 'create-step-bar',
        title: '创作步骤',
        description: '顶部步骤条展示全流程进度。',
        side: 'bottom'
      },
      {
        id: 'cgs-2',
        element: 'global-setting-form',
        title: '项目配置',
        description: '画面比例、风格、模型策略等影响全片。',
        side: 'top'
      },
      {
        id: 'cgs-3',
        element: 'btn-next-step',
        title: '下一步',
        description: '保存设定后进入剧本创作。',
        side: 'bottom',
        advanceOn: 'click-target'
      }
    ]
  },
  {
    id: 'create-scene-character',
    version: 2,
    type: 'spotlight',
    trigger: { route: /^\/create\/scene-character/, priority: 1, requiresLogin: true },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'scp-1',
        element: 'scp-tabs',
        title: '角色/场景/道具',
        description: '切换三类资产编辑。',
        side: 'bottom'
      },
      {
        id: 'scp-2',
        element: 'extract-agent-start',
        title: '智能提取',
        description: '从剧本自动提取角色、场景、道具，点击「开始提取」。',
        side: 'top'
      },
      {
        id: 'scp-3',
        element: 'scp-batch-ops',
        title: '批量操作',
        description: '提取完成后，可在此进行批量生成与管理。',
        side: 'bottom'
      },
      {
        id: 'scp-4',
        element: 'scp-batch-form-generate',
        title: '批量生成形态',
        description: '一键为当前 Tab 下全部资产生成形态描述。',
        side: 'left',
        waitTimeoutMs: 3000
      },
      {
        id: 'scp-5',
        element: 'scp-batch-image-generate',
        title: '批量生成图片',
        description: '形态确认后，可批量生成场景/角色/道具图。',
        side: 'left',
        waitTimeoutMs: 3000
      }
    ]
  },
  {
    id: 'create-storyboard-video',
    version: 4,
    type: 'spotlight',
    trigger: { route: /^\/create\/storyboard-video/, priority: 1, requiresLogin: true },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'sbv-1',
        element: 'video-panel-list',
        title: '分镜视频区',
        description: '为每个镜头生成或上传视频。',
        side: 'top'
      },
      {
        id: 'sbv-2',
        element: 'sbv-batch-video',
        title: '批量生成分镜视频',
        description: '在「批量操作」中可批量提交视频生成任务。',
        side: 'left',
        waitTimeoutMs: 3000
      },
      {
        id: 'sbv-3',
        element: 'btn-next-step',
        title: '下一步',
        description: '视频就绪后进入音画同步。',
        side: 'bottom',
        advanceOn: 'click-target'
      }
    ]
  },
  {
    id: 'create-dubbing',
    version: 2,
    type: 'spotlight',
    trigger: { route: /^\/create\/dubbing/, priority: 1, requiresLogin: true },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'dub-1',
        element: 'dubbing-track',
        title: '配音轨道',
        description: '为镜头配置旁白或对白。',
        side: 'top'
      },
      {
        id: 'dub-2',
        element: 'dub-batch-dubbing',
        title: '批量生成分镜配音',
        description: '在「批量操作」中可批量生成分镜配音。',
        side: 'left',
        waitTimeoutMs: 3000
      },
      {
        id: 'dub-3',
        element: 'btn-next-step',
        title: '下一步',
        description: '进入最终预览与提交审核。',
        side: 'bottom',
        advanceOn: 'click-target'
      }
    ]
  },
  {
    id: 'create-preview',
    version: 1,
    type: 'spotlight',
    trigger: { route: /^\/create\/preview/, priority: 1, requiresLogin: true },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'prev-1',
        element: 'preview-player',
        title: '成片预览',
        description: '完整播放检查节奏与画面。',
        side: 'bottom'
      },
      {
        id: 'prev-2',
        element: 'preview-timeline',
        title: '时间轴',
        description: '可微调镜头顺序与时长。',
        side: 'top'
      },
      {
        id: 'prev-3',
        element: 'btn-export-video',
        title: '导出/发布',
        description: '点击导出完整视频（成功后自动保存到本地）、导出分段素材，或发布至案例广场。',
        side: 'bottom'
      }
    ]
  },
  {
    id: 'create-series-upload',
    version: 1,
    type: 'spotlight',
    trigger: {
      route: /^\/create\/series-script-upload/,
      priority: 2,
      requiresLogin: true,
      seriesOnly: true
    },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'ssu-1',
        element: 'series-upload-area',
        title: '上传剧本',
        description: '支持 Word 等格式批量导入。',
        side: 'top'
      },
      {
        id: 'ssu-2',
        element: 'series-parse-result',
        title: '解析并进入分集列表',
        description: '点击解析后自动分集、提取资产，并进入分集列表管理各集创作。',
        side: 'top',
        waitFor: '[data-onboarding="series-parse-result"]',
        waitTimeoutMs: 3000,
        advanceOn: 'click-target'
      }
    ]
  },
  {
    id: 'create-series-episode',
    version: 1,
    type: 'spotlight',
    trigger: {
      route: /^\/create\/series-episode-list/,
      priority: 2,
      requiresLogin: true,
      seriesOnly: true
    },
    prerequisites: ['welcome-overview'],
    spotlightSteps: [
      {
        id: 'sep-1',
        element: 'episode-list',
        title: '分集列表',
        description: '每集独立走创作流程。',
        side: 'top'
      },
      {
        id: 'sep-2',
        element: 'episode-enter',
        title: '进入单集创作',
        description: '点击「生成剧集」开始编辑该集。',
        side: 'left',
        advanceOn: 'click-target',
        waitFor: '[data-onboarding="episode-enter"]',
        waitTimeoutMs: 5000
      }
    ]
  }
]
