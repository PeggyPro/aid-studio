export const HOME_GUEST_LOGIN_REDIRECT = '/'
export const HOME_GUEST_LOGIN_MESSAGE_KEY = 'home-guest-login-required'

export function formatHomeGuestLoginMessage(actionName?: string): string {
  const action = String(actionName || '').trim()
  return action ? `请先登录后${action}` : '请先登录后使用该功能'
}
