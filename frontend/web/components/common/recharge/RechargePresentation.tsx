import { QrcodeOutlined, WalletOutlined } from '@ant-design/icons'

export const rechargeModalClasses = {
  container: 'recharge-dialog__panel',
  header: 'recharge-dialog__header',
  body: 'recharge-dialog__body',
  close: 'recharge-dialog__close'
}

export function RechargeTitle({ payment = false }: { payment?: boolean }) {
  const Icon = payment ? QrcodeOutlined : WalletOutlined
  return (
    <div className="recharge-dialog__heading">
      <span className="recharge-dialog__emblem"><Icon aria-hidden /></span>
      <span>{payment ? '扫码支付' : '积分充值'}</span>
    </div>
  )
}

export function RechargeAmount({ value }: { value: string }) {
  return (
    <span className="recharge-amount">
      <span className="recharge-amount__currency">¥</span>
      <span>{value}</span>
    </span>
  )
}
