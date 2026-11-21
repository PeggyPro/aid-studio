import React from 'react';
import { Empty } from 'antd';

const hidden = /^(storageUrl|token|accessToken|refreshToken|authToken|apiKey|secret|secretKey|password|authorization|credentials)$/i;
const labels: Record<string, string> = {
  objectId: '对象编号',
  objectType: '对象类型',
  content: '固定正文',
  nodeType: '节点类型',
  title: '标题',
  textContent: '完整文本',
  prompt: '提示词',
  negativePrompt: '负向提示词',
  parameters: '参数',
  metadata: '扩展资料',
  position: '位置',
  rotation: '旋转',
  scale: '比例',
  transform: '变换',
  size: '尺寸',
  width: '宽度',
  height: '高度',
  resourceIds: '引用资源',
  sourceObjectId: '起点对象',
  targetObjectId: '终点对象',
  sourceHandle: '起点端口',
  targetHandle: '终点端口',
  childObjectIds: '分组成员',
  label: '连线说明',
  style: '样式',
  resourceId: '资源编号',
  resourceUrl: '审核资源路径',
  mediaType: '媒体类型',
  fileSize: '文件大小',
  mimeType: '文件格式',
  bundle: '完整资源包',
  format: '格式',
  rootPath: '入口文件',
  files: '冻结文件',
  references: '依赖引用',
  ownerPath: '引用所在文件',
  uri: '原依赖标识',
  path: '文件路径',
  sceneDocument: '完整场景',
  scenes: '场景',
  objects: '对象',
  camera: '固定相机',
  cameraBookmarks: '保存机位',
  lights: '灯光',
  timeline: '时间线',
  currentTime: '固定时间',
  duration: '总时长',
  tracks: '变换轨道',
  poseTracks: '姿态轨道',
  cameraTracks: '相机轨道',
  actionClips: '动作片段',
  pathClips: '路径片段',
  keyframes: '关键帧',
  time: '时间',
  value: '取值',
  pose: '姿态',
  modelAssets: '模型素材',
  modelAssetId: '模型素材编号',
  scenePackage: '场景包',
  scenePackages: '场景包集合',
  scenery: '场景部件',
  scenePackageResourceId: '场景包资源编号',
  meshPath: '部件路径',
  waypoints: '路径点',
  follow: '跟随约束',
  blendIn: '过渡时长',
  action: '动作',
  reason: '原因',
  available: '可比较',
  sameContent: '正文一致',
  sameScenes: '场景一致',
  sameCurrentResults: '选中结果一致',
  sameResources: '资源一致',
  samePresentation: '展示资料一致',
  addedObjectIds: '新增对象',
  modifiedObjectIds: '修改对象',
  removedObjectIds: '移除对象'
};
/** Read-only expandable fields retain every structural value without rendering HTML. */
export default function InspectData({ value, label = '字段' }: { value: any; label?: string }) {
  if (value == null) return <span className="canvas-audit-muted">未提供</span>;
  if (typeof value !== 'object')
    return (
      <span className="canvas-audit-value">{typeof value === 'boolean' ? (value ? '是' : '否') : String(value)}</span>
    );
  const entries = Object.entries(value).filter(([key]) => !hidden.test(key));
  if (!entries.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无字段" />;
  return (
    <div className="canvas-audit-fields">
      {entries.map(([key, child]) =>
        typeof child === 'object' && child !== null ? (
          <details key={key}>
            <summary>
              {Array.isArray(value) ? `${labels[label] || label} ${Number(key) + 1}` : labels[key] || key}{' '}
              {Array.isArray(child) ? `（${child.length} 项）` : ''}
            </summary>
            <InspectData value={child} label={key} />
          </details>
        ) : (
          <div className="canvas-audit-field" key={key}>
            <strong title={key}>
              {Array.isArray(value) ? `${labels[label] || label} ${Number(key) + 1}` : labels[key] || key}
            </strong>
            <InspectData value={child} />
          </div>
        )
      )}
    </div>
  );
}
