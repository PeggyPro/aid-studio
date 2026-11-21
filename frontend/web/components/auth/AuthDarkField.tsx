'use client'

import { EyeInvisibleOutlined, EyeOutlined } from '@ant-design/icons'
import { useState, type ReactNode } from 'react'

export type AuthDarkFieldSurface = 'ghost' | 'solid'

export interface AuthDarkFieldProps {
  type?: 'text' | 'password'
  name?: string
  value: string
  placeholder: string
  maxLength?: number
  inputMode?: 'numeric' | 'text'
  autoComplete?: string
  suffix?: ReactNode
  /** ghost：登录弹窗描边半透明底；solid：找回密码实心底 */
  surface?: AuthDarkFieldSurface
  showPasswordToggle?: boolean
  onChange: (value: string) => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

const SURFACE_WRAP: Record<AuthDarkFieldSurface, string> = {
  ghost: 'auth-dark-field-wrap auth-dark-field-wrap--ghost',
  solid: 'auth-dark-field-wrap auth-dark-field-wrap--solid'
}

/** 外框拥有唯一的填充和焦点环，原生输入只负责文字与编辑，避免嵌套控件重复绘制。 */
export function AuthDarkField({
  type = 'text',
  name,
  value,
  placeholder,
  maxLength,
  inputMode,
  autoComplete,
  suffix,
  surface = 'ghost',
  showPasswordToggle = false,
  onChange,
  onUnlockAutofill
}: AuthDarkFieldProps) {
  const isPassword = type === 'password' || showPasswordToggle
  const [passwordVisible, setPasswordVisible] = useState(false)
  const shared = {
    name,
    value,
    placeholder,
    maxLength,
    readOnly: true,
    autoComplete: isPassword ? autoComplete || 'new-password' : autoComplete || 'off',
    className: 'auth-dark-field-input',
    onFocus: onUnlockAutofill,
    onMouseDown: onUnlockAutofill,
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => onChange(e.target.value)
  }

  return (
    <div className={SURFACE_WRAP[surface]}>
      <input {...shared} type={isPassword && !passwordVisible ? 'password' : 'text'} inputMode={inputMode} aria-label={placeholder} />
      {showPasswordToggle ? <button type="button" className="auth-dark-field-toggle" aria-label={passwordVisible ? '隐藏密码' : '显示密码'} aria-pressed={passwordVisible} onClick={() => setPasswordVisible((value) => !value)}>{passwordVisible ? <EyeOutlined /> : <EyeInvisibleOutlined />}</button> : null}
      {suffix ? <span className="auth-dark-field-suffix">{suffix}</span> : null}
    </div>
  )
}
