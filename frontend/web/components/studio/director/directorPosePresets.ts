/**
 * Maps storyai-3d-director-desk pose control keys to aid-pc flat joint keys.
 */
export function mapStoryaiControlsToDirectorPose(controls: Record<string, number>): Record<string, number> {
  const value = (key: string) => controls[key] ?? 0
  const pose: Record<string, number> = {}
  if (value('root.height')) pose.rootHeight = value('root.height')

  const spine = value('body.pitch') * 0.55 + value('torso.pitch') * 0.45
  if (spine) pose.spine = -spine

  const chest = value('torso.yaw') || value('torso.pitch') * 0.65
  if (chest) pose.chest = chest

  if (value('head.roll')) pose.neck = value('head.roll')
  if (value('head.pitch')) pose.headX = value('head.pitch')
  if (value('head.yaw')) pose.headY = value('head.yaw')

  const leftShoulder = value('leftShoulder.pitch') + value('leftShoulder.spread') * 0.42 + value('leftShoulder.twist') * 0.18
  const rightShoulder = value('rightShoulder.pitch') + value('rightShoulder.spread') * 0.42 + value('rightShoulder.twist') * 0.18
  if (leftShoulder) pose.leftShoulder = leftShoulder
  if (rightShoulder) pose.rightShoulder = rightShoulder

  if (value('leftElbow.bend')) pose.leftElbow = value('leftElbow.bend')
  if (value('rightElbow.bend')) pose.rightElbow = value('rightElbow.bend')

  if (value('leftHand.roll') || value('leftHand.pitch')) pose.leftWrist = value('leftHand.roll') * 0.7 + value('leftHand.pitch') * 0.3
  if (value('rightHand.roll') || value('rightHand.pitch')) pose.rightWrist = value('rightHand.roll') * 0.7 + value('rightHand.pitch') * 0.3
  if (value('leftHand.twist')) pose.leftThumb = value('leftHand.twist') * 0.45
  if (value('rightHand.twist')) pose.rightThumb = value('rightHand.twist') * 0.45

  if (value('leftHip.pitch') || value('leftHip.spread')) pose.leftHip = value('leftHip.pitch') + value('leftHip.spread') * 0.35
  if (value('rightHip.pitch') || value('rightHip.spread')) pose.rightHip = value('rightHip.pitch') + value('rightHip.spread') * 0.35

  if (value('leftKnee.bend')) pose.leftKnee = value('leftKnee.bend')
  if (value('rightKnee.bend')) pose.rightKnee = value('rightKnee.bend')

  if (value('leftFoot.pitch')) pose.leftAnkle = value('leftFoot.pitch')
  if (value('rightFoot.pitch')) pose.rightAnkle = value('rightFoot.pitch')

  return pose
}

export const DIRECTOR_POSE_PRESET_DEFINITIONS: Array<{ label: string; controls: Record<string, number> }> = [
  { label: '站立', controls: {} },
  { label: 'T型', controls: { 'leftShoulder.spread': -70, 'rightShoulder.spread': 70, 'leftShoulder.pitch': 15, 'rightShoulder.pitch': 15, 'leftElbow.bend': 10, 'rightElbow.bend': 10 } },
  { label: '行走', controls: { 'leftShoulder.pitch': 20, 'rightShoulder.pitch': -20, 'leftHip.pitch': -20, 'rightHip.pitch': 20, 'leftKnee.bend': 12, 'rightKnee.bend': 4 } },
  { label: '跑步', controls: { 'leftShoulder.pitch': 42, 'rightShoulder.pitch': -42, 'leftHip.pitch': -35, 'rightHip.pitch': 40, 'leftKnee.bend': 28, 'rightKnee.bend': 18 } },
  { label: '坐姿', controls: { 'root.height': -.45, 'leftHip.pitch': 90, 'rightHip.pitch': 90, 'leftKnee.bend': 90, 'rightKnee.bend': 90 } },
  { label: '蹲下', controls: { 'root.height': -.68, 'body.pitch': 18, 'torso.pitch': 18, 'head.pitch': 28, 'leftHip.pitch': 117, 'rightHip.pitch': 117, 'leftKnee.bend': 135, 'rightKnee.bend': 135, 'leftFoot.pitch': 36, 'rightFoot.pitch': 36, 'leftShoulder.pitch': 35, 'rightShoulder.pitch': 35, 'leftElbow.bend': 120, 'rightElbow.bend': 120 } },
  { label: '单膝跪', controls: { 'body.pitch': -16, 'torso.pitch': -10, 'head.pitch': 12, 'leftHip.pitch': 68, 'leftKnee.bend': 86, 'leftFoot.pitch': 20, 'rightHip.pitch': -15, 'rightKnee.bend': 80, 'rightFoot.pitch': 60, 'leftShoulder.pitch': 5, 'leftShoulder.spread': 10, 'leftShoulder.twist': -10, 'leftElbow.bend': 30, 'rightShoulder.pitch': -18, 'rightShoulder.spread': 10, 'rightElbow.bend': 18 } },
  { label: '双膝跪', controls: { 'body.pitch': 2, 'torso.pitch': 8, 'head.pitch': -2, 'leftShoulder.pitch': -10, 'rightShoulder.pitch': -10, 'leftShoulder.spread': -5, 'rightShoulder.spread': 5, 'leftElbow.bend': 8, 'rightElbow.bend': 8, 'leftHip.pitch': -8, 'rightHip.pitch': -8, 'leftKnee.bend': 126, 'rightKnee.bend': 126, 'leftFoot.pitch': -20, 'rightFoot.pitch': -20 } },
  { label: '叉腰', controls: { 'leftShoulder.pitch': -36, 'rightShoulder.pitch': -36, 'leftShoulder.twist': 80, 'rightShoulder.twist': -80, 'leftElbow.bend': 86, 'rightElbow.bend': 86, 'leftHand.roll': -35, 'rightHand.roll': 35 } },
  { label: '倚靠', controls: { 'body.roll': -10, 'leftHip.spread': -8, 'rightHip.spread': 8, 'head.roll': 6 } },
  { label: '鞠躬', controls: { 'body.pitch': -46, 'torso.pitch': -10, 'head.pitch': 20, 'leftHip.pitch': 49, 'rightHip.pitch': 49, 'leftShoulder.pitch': 5, 'rightShoulder.pitch': 5, 'leftShoulder.spread': 10, 'rightShoulder.spread': -10, 'leftElbow.bend': 12, 'rightElbow.bend': 12 } },
  { label: '思考', controls: { 'rightShoulder.pitch': 8, 'rightShoulder.twist': -40, 'rightElbow.bend': 90, 'rightHand.roll': -40, 'rightHand.pitch': 15, 'rightHand.twist': -10, 'leftShoulder.pitch': 8, 'leftShoulder.twist': 40, 'leftElbow.bend': 90 } },
  { label: '格斗', controls: { 'body.yaw': -10, 'body.pitch': 5, 'torso.yaw': 8, 'head.yaw': 8, 'leftShoulder.pitch': 48, 'leftShoulder.spread': -16, 'leftShoulder.twist': 22, 'rightShoulder.pitch': 30, 'rightShoulder.twist': -22, 'leftElbow.bend': 86, 'rightElbow.bend': 84, 'leftHip.spread': -18, 'rightHip.spread': 22, 'leftHip.pitch': 4, 'rightHip.pitch': -6, 'leftKnee.bend': 12, 'rightKnee.bend': 18 } },
  { label: '踢球', controls: { 'leftHip.pitch': -8, 'rightHip.pitch': 58, 'rightKnee.bend': 35, 'leftShoulder.pitch': 18, 'rightShoulder.pitch': -24 } },
  { label: '投掷', controls: { 'body.pitch': 5, 'body.yaw': 14, 'torso.yaw': -10, 'head.yaw': 8, 'rightShoulder.pitch': 76, 'rightShoulder.spread': -14, 'rightShoulder.twist': 28, 'rightElbow.bend': 86, 'rightHand.roll': 18, 'rightHand.pitch': -12, 'leftShoulder.pitch': 34, 'leftShoulder.spread': 10, 'leftShoulder.twist': 8, 'leftElbow.bend': 54, 'leftHand.pitch': -10, 'leftHip.spread': -12, 'rightHip.spread': 18, 'leftHip.pitch': 24, 'rightHip.pitch': -10, 'leftKnee.bend': 30, 'rightKnee.bend': 14, 'leftFoot.pitch': -8 } },
  { label: '推进', controls: { 'body.pitch': 5, 'body.yaw': 38, 'torso.pitch': -4, 'head.pitch': 6, 'leftShoulder.pitch': 92, 'rightShoulder.pitch': 92, 'leftShoulder.spread': -11, 'rightShoulder.spread': 11, 'leftElbow.bend': 6, 'rightElbow.bend': 6, 'leftHand.pitch': -14, 'rightHand.pitch': -14, 'leftHip.spread': -12, 'rightHip.spread': 14, 'leftHip.pitch': 38, 'rightHip.pitch': -20, 'leftKnee.bend': 42, 'rightKnee.bend': 20, 'leftFoot.pitch': -6 } },
  { label: '招手', controls: { 'rightShoulder.pitch': 60, 'rightShoulder.twist': 30, 'rightElbow.bend': 90, 'rightHand.roll': -20, 'rightHand.pitch': 12, 'rightHand.twist': 10, 'leftShoulder.pitch': -10, 'leftShoulder.spread': 8, 'leftElbow.bend': 18, 'leftHand.pitch': -8 } },
  { label: '伸手', controls: { 'rightShoulder.pitch': 50, 'rightElbow.bend': 12 } },
  { label: '抱臂', controls: { 'leftShoulder.pitch': 50, 'leftShoulder.spread': -55, 'leftShoulder.twist': 75, 'leftElbow.bend': 50, 'leftHand.pitch': -10, 'rightShoulder.pitch': 90, 'rightShoulder.spread': 55, 'rightShoulder.twist': -45, 'rightElbow.bend': 50, 'rightHand.roll': 18, 'rightHand.pitch': -10 } },
  { label: '看手机', controls: { 'head.pitch': 18, 'rightShoulder.pitch': 20, 'rightShoulder.spread': -4, 'rightShoulder.twist': -30, 'rightElbow.bend': 82, 'rightHand.roll': -30, 'rightHand.pitch': 14, 'rightHand.twist': 60, 'leftShoulder.pitch': -10, 'leftShoulder.spread': 8, 'leftElbow.bend': 16, 'leftHand.pitch': -8 } }
]

export function buildDirectorPosePresets(): Record<string, Record<string, number>> {
  return Object.fromEntries(
    DIRECTOR_POSE_PRESET_DEFINITIONS.map((preset) => [
      preset.label,
      mapStoryaiControlsToDirectorPose(preset.controls)
    ])
  )
}
