import React, { type ReactNode } from 'react';
import { App, Button, Result } from 'antd';

/** Keep navigation available after a failed route import; never reload an editing page automatically. */
class RouteBoundary extends React.Component<{ children: ReactNode; reload: () => void }, { failed: boolean; moduleUnavailable: boolean }> {
  state = { failed: false, moduleUnavailable: false };

  static getDerivedStateFromError(error: unknown) {
    const message = error instanceof Error ? error.message : '';
    return {
      failed: true,
      moduleUnavailable: /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk [^\n]+ failed|ChunkLoadError/i.test(message),
    };
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return <section role="alert" aria-label="页面加载恢复">
      <Result status="warning" title={this.state.moduleUnavailable ? '页面资源加载失败' : '页面暂时无法显示'}
        subTitle={this.state.moduleUnavailable
          ? '页面资源可能已更新或网络连接中断。您可以继续使用导航；重新加载后将读取当前页面资源。'
          : '请重新加载此页面后再试。重复出现时，请记录当前操作并联系管理员。'}
        extra={<Button type="primary" onClick={this.props.reload}>重新加载页面</Button>} />
    </section>;
  }
}

export default function RouteRecoveryBoundary({ children }: { children: ReactNode }) {
  const { modal } = App.useApp();
  const reload = () => modal.confirm({
    title: '重新加载当前页面？',
    content: '刷新会丢失尚未保存的表单修改。请先确认需要保留的内容已保存。',
    okText: '重新加载',
    cancelText: '暂不刷新',
    onOk: () => window.location.reload(),
  });
  return <RouteBoundary reload={reload}>{children}</RouteBoundary>;
}
