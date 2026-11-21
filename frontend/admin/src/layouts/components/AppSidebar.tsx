import React, { useMemo, useState } from 'react';
import { Menu, Dropdown, Avatar, Modal, ConfigProvider, theme } from 'antd';
import type { MenuProps } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import { UserOutlined, LogoutOutlined, DownOutlined } from '@ant-design/icons';

import { usePermissionStore, AppRoute } from '@/store/usePermissionStore';
import { useUserStore } from '@/store/useUserStore';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';
import { resolvePath } from '@/router/utils';
import { resolveAppUrl } from '@/utils/ruoyi';
import MenuIcon from './MenuIcon';
import { organizeNavigation, type NavigationItem } from '@/utils/adminNavigation';
import { authoringKind, getAuthoringAccess, type AuthoringAccess } from '@/utils/adminWorkspaces';
import { useAuth } from '@/hooks/useAuth';
import SidebarVersionPanel from './SidebarVersionPanel';
import './AppSidebar.less';

interface Props {
  collapsed: boolean;
}

type MenuItem = NonNullable<NonNullable<MenuProps['items']>[number]>;

function buildSidebar(routes: AppRoute[], authoring: AuthoringAccess) {
  const parentKeys = new Map<string, string[]>();
  const menuKeys = new Map<string, string>();
  function collect(route: AppRoute, base: string, icon?: string, fallback?: string): NavigationItem<AppRoute>[] {
    if (route.hidden) return [];
    const key = resolvePath(base, route.path);
    const title = route.meta?.title || fallback || '';
    const itemIcon = route.meta?.icon || icon;
    const children = (route.children || []).filter(child => !child.hidden);
    if (children.length === 1 && !route.alwaysShow) return collect(children[0], key, itemIcon, title);
    if (route.children?.length) {
      const nested = children.flatMap(child => collect(child, key));
      return nested.length ? [{ key, title, icon: itemIcon, children: nested }] : [];
    }
    if (!route.component && (route.isLayout || route.isParentView || !route.meta)) return [];
    return [{ key, title, icon: itemIcon, view: route.view }];
  }
  function render(item: NavigationItem<AppRoute>, parents: string[]): MenuItem {
    const entry = { key: item.key, label: item.title, icon: item.icon ? <MenuIcon icon={item.icon} /> : undefined };
    if (item.children) return { ...entry, children: item.children.map(child => render(child, [...parents, item.key])) };
    parentKeys.set(item.key, parents);
    item.aliases?.forEach(path => menuKeys.set(path, item.key));
    return entry;
  }
  const items = organizeNavigation(routes.flatMap(route => collect(route, '')), authoring).map(item => render(item, []));
  return { items, parentKeys, menuKeys };
}

export default function AppSidebar({ collapsed }: Props) {
  const { token } = theme.useToken();
  const sidebarRouters = usePermissionStore((s) => s.sidebarRouters);
  const location = useLocation();
  const navigate = useNavigate();
  const { nickName, avatar, logout } = useUserStore();
  const sidebarLogo = useAdminBrandStore((s) => s.resolvedSidebarLogo);
  const siteName = useAdminBrandStore((s) => s.resolvedSiteName);

  const { hasPermi } = useAuth();
  const { skill, agent } = getAuthoringAccess(hasPermi);
  const { items: menuItems, parentKeys, menuKeys } = useMemo(
    () => buildSidebar(sidebarRouters, { skill, agent }), [sidebarRouters, skill, agent]
  );
  const [opened, setOpened] = useState<{ path: string; keys: string[] }>();
  const kind = authoringKind(location.search);
  const selectedKey = (kind ? menuKeys.get(location.pathname + '?kind=' + kind) : undefined)
    ?? menuKeys.get(location.pathname) ?? location.pathname;
  const openKeys = opened?.path === selectedKey ? opened.keys : parentKeys.get(selectedKey) ?? [];

  const handleClick: MenuProps['onClick'] = ({ key }) => {
    if (key.startsWith('http://') || key.startsWith('https://')) {
      window.open(key, '_blank');
      return;
    }
    navigate(key);
  };

  const handleUserMenu: MenuProps['onClick'] = async ({ key }) => {
    if (key === 'profile') {
      navigate('/user/profile');
    } else if (key === 'logout') {
      Modal.confirm({
        title: '提示',
        content: '确定注销并退出系统吗？',
        okText: '确定',
        cancelText: '取消',
        onOk: async () => {
          await logout();
          window.location.href = resolveAppUrl('/login');
        }
      });
    }
  };

  return (
    <div className={`app-sidebar ${collapsed ? 'is-collapsed' : ''}`}>
      <div className="app-sidebar__logo">
        <img src={sidebarLogo} alt="logo" />
        {!collapsed && (
          <div className="app-sidebar__brand">
            <span className="app-sidebar__title">{siteName}</span>
            <SidebarVersionPanel collapsed={collapsed} />
          </div>
        )}
      </div>

      <div className="app-sidebar__menu-wrap">
        <ConfigProvider theme={{ components: { Menu: { zIndexPopup: token.zIndexPopupBase - 1 } } }}>
        <Menu
          mode="inline"
          theme="light"
          items={menuItems}
          selectedKeys={[selectedKey]}
          openKeys={collapsed ? undefined : openKeys}
          onOpenChange={keys => { if (!collapsed) setOpened({ path: selectedKey, keys }); }}
          onClick={handleClick}
          inlineCollapsed={collapsed}
          className="app-sidebar__menu"
        />
        </ConfigProvider>
      </div>

      <Dropdown
        placement="topRight"
        trigger={['click']}
        menu={{
          items: [
            { key: 'profile', icon: <UserOutlined />, label: '个人中心' },
            { type: 'divider' },
            { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }
          ],
          onClick: handleUserMenu
        }}
      >
        <div className={`app-sidebar__user ${collapsed ? 'is-collapsed' : ''}`}>
          <Avatar size={collapsed ? 32 : 34} src={avatar} icon={!avatar && <UserOutlined />} />
          {!collapsed && (
            <>
              <span className="app-sidebar__user-name">{nickName || '-'}</span>
              <DownOutlined className="app-sidebar__user-arrow" />
            </>
          )}
        </div>
      </Dropdown>
    </div>
  );
}
