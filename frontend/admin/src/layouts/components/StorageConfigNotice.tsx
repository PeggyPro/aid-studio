import React, { useEffect, useState } from 'react';
import { Alert, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
import { usePermissionStore, type AppRoute } from '@/store/usePermissionStore';
import { getStorageStatus, STORAGE_STATUS_CHANGED, type StorageState } from '@/api/aidconfig/storageStatus';

function findConfigPath(routes: AppRoute[], parent = ''): string | undefined {
  for (const route of routes) {
    const path = route.path.startsWith('/') ? route.path : `${parent}/${route.path}`;
    if (route.view === 'aidconfig/aidconfig/index' || route.view === 'aidconfig/aidconfig') return path;
    const child = route.children && findConfigPath(route.children, path);
    if (child) return child;
  }
  return undefined;
}

export default function StorageConfigNotice() {
  const [state, setState] = useState<StorageState | 'UNKNOWN'>();
  const [checking, setChecking] = useState(false);
  const routes = usePermissionStore((s) => s.routes);
  const navigate = useNavigate();
  const path = findConfigPath(routes);

  useEffect(() => {
    let alive = true;
    const check = (force = false) => {
      if (alive) setChecking(true);
      void getStorageStatus(force).then((value) => { if (alive) setState(value); })
        .catch(() => { if (alive) setState('UNKNOWN'); })
        .finally(() => { if (alive) setChecking(false); });
    };
    const changed = () => check(true);
    const visible = () => { if (!document.hidden) check(); };
    check();
    const timer = window.setInterval(visible, 60000);
    window.addEventListener(STORAGE_STATUS_CHANGED, changed);
    document.addEventListener('visibilitychange', visible);
    return () => {
      alive = false;
      clearInterval(timer);
      window.removeEventListener(STORAGE_STATUS_CHANGED, changed);
      document.removeEventListener('visibilitychange', visible);
    };
  }, []);

  if (!state || state === 'READY') return null;
  const text = state === 'UNCONFIGURED'
    ? '文件存储的资源访问地址尚未正确配置。请手动填写地址、保存并同步配置，否则图片等资源可能无法显示或用于生成。'
    : state === 'UNSYNCED'
      ? '文件存储配置尚未同步。保存不等于生效，请进入文件存储，核对资源访问地址后点击“同步配置”。'
      : '文件存储配置状态暂未确认，请重试检查；确认配置生效后提示才会消失。';
  return (
    <Alert className="app-layout__storage-notice" role="alert" showIcon type="warning"
      message={text}
      description={!path && '请联系有全局业务配置权限的管理员完成配置。'}
      action={state === 'UNKNOWN'
        ? <Button size="small" loading={checking} onClick={() => window.dispatchEvent(new Event(STORAGE_STATUS_CHANGED))}>重新检查</Button>
        : path && <Button size="small" onClick={() => navigate(`${path}?category=oss&focus=resourceAccessDomain`)}>前往资源访问地址</Button>}
    />
  );
}
