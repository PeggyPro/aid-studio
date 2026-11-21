/**
 * 本地 Mock 只允许在显式演示构建中启用。生产环境不能因为入口存在就伪装成真实能力。
 * Next 会在构建时内联 NEXT_PUBLIC_*，部署侧必须明确设置 true。
 */
export function isStudioLocalDemoEnabled(): boolean {
  return process.env.NEXT_PUBLIC_STUDIO_DEMO_MODE === 'true'
}

export const STUDIO_LOCAL_DEMO_UNAVAILABLE_MESSAGE =
  '当前生成能力尚未接入真实服务；画布编辑、分组、连线和参数配置仍可正常使用'
