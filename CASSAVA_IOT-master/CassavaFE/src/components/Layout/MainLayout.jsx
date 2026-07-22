import React, { useState, useEffect } from 'react';
import { Layout, Menu, theme, Typography, Button, Dropdown, Space, Avatar, Drawer } from 'antd';
import {
  AppstoreOutlined,
  ApartmentOutlined,
  CloudOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined, // Thêm icon này
  DownOutlined  // Thêm icon này
} from '@ant-design/icons';
import { useNavigate, Outlet, useLocation, Navigate } from 'react-router-dom';
import logoUet from '../../assets/images/logo-uet.png';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;
const MainLayout = () => {
  const [collapsed, setCollapsed] = useState(false);
  const [isMobile, setIsMobile] = useState(window.innerWidth < 768);
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    const handleResize = () => {
      const mobile = window.innerWidth < 768;
      setIsMobile(mobile);
      if (!mobile) setDrawerOpen(false);
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);
// 1. Lấy thông tin user từ localStorage để kiểm tra quyền
  const userData = JSON.parse(localStorage.getItem("user") || 'null');
  // Guard: chưa đăng nhập (hoặc đã xoá session) → đẩy về /login
  if (!userData || !userData.accessToken) {
    return <Navigate to="/login" replace />;
  }
  const isAdmin = userData?.isAdmin == true; //// Kiểm tra trường admin trong Token/User
  //const isAdmin = true;

// 1. Giả lập dữ liệu User để test (Nếu chưa có trong localStorage)
  // Cậu có thể mở F12 -> Application -> Local Storage để xem/thêm dữ liệu này
 // const userData = JSON.parse(localStorage.getItem("user")) || { username: "Kiên Admin", admin: true };
 // const isAdmin = userData?.admin === true;  
  const navigate = useNavigate();
  const location = useLocation();
  // thêm mới
  const userMenuItems = [
  {
    key: 'profile',
    label: 'Thông tin cá nhân',
    icon: <UserOutlined />,
    onClick: () => navigate('/profile'), // Điều hướng tới trang cá nhân
  },
  {
    type: 'divider', // Dấu gạch ngang phân cách
  },
  {
    key: 'logout',
    label: 'Đăng xuất',
    icon: <LogoutOutlined />,
    danger: true, // Chữ màu đỏ cho nổi bật
    onClick: () => {
      localStorage.removeItem("user"); // Xóa data khi đăng xuất
      navigate('/login');
    },
  },
];

  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken();

  // Cấu hình các mục trong Menu (Sidebar)
  const menuItems = [
    {
      key: '/fields',
      icon: <AppstoreOutlined />,
      label: 'Quản lý cánh đồng',
    },
    {
      key: '/field-groups',
      icon: <ApartmentOutlined />,
      label: 'Nhóm cánh đồng',
    },
    {
      key: '/weather',
      icon: <CloudOutlined />,
     label: 'Dữ liệu thời tiết',
   },
   isAdmin ? {
      key: '/users',
      icon: <AppstoreOutlined />,
      label: 'Danh sách người dùng',
    } : null

  ];

  const handleMenuClick = ({ key }) => {
    navigate(key);
  };

  const handleLogout = () => {
    // TODO: Xóa token đăng nhập ở local storage nếu có
    navigate('/login');
  };

  const siderContent = (
    <>
      <div style={{
        height: 88,
        display: 'flex',
        alignItems: 'center',
        justifyContent: collapsed && !isMobile ? 'center' : 'flex-start',
        padding: collapsed && !isMobile ? '0' : '0 14px',
        borderBottom: '1px solid rgba(255,255,255,0.08)',
        transition: 'all 0.2s',
        overflow: 'hidden'
      }}>
        <img
          src={logoUet}
          alt="logo"
          style={{
            width: collapsed && !isMobile ? 52 : 60,
            height: collapsed && !isMobile ? 52 : 60,
            marginRight: collapsed && !isMobile ? 0 : 10,
            objectFit: 'cover',
            clipPath: 'circle(50%)',
            flexShrink: 0,
            transition: 'all 0.2s'
          }}
        />
        {(!collapsed || isMobile) && (
          <span
            style={{
              color: 'white',
              fontSize: 17,
              fontWeight: 600,
              letterSpacing: 0.3,
              whiteSpace: 'nowrap',
              lineHeight: 1.2,
            }}
          >
            SMART FARMING
          </span>
        )}
      </div>
      <Menu
        theme="dark"
        mode="inline"
        selectedKeys={[location.pathname]}
        onClick={(info) => {
          handleMenuClick(info);
          if (isMobile) setDrawerOpen(false);
        }}
        items={menuItems}
      />
    </>
  );

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {isMobile ? (
        <Drawer
          placement="left"
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          width={250}
          styles={{ body: { padding: 0, background: '#001529' } }}
          closable={false}
        >
          {siderContent}
        </Drawer>
      ) : (
        <Sider trigger={null} collapsible collapsed={collapsed} theme="dark" width={250}>
          {siderContent}
        </Sider>
      )}

      <Layout>
        <Header style={{ height: 88, lineHeight: '88px', padding: 0, background: colorBgContainer, display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingRight: 16, boxShadow: '0 1px 4px rgba(0,0,0,0.06)' }}>
          <Button
            type="text"
            icon={isMobile || collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => isMobile ? setDrawerOpen(true) : setCollapsed(!collapsed)}
            style={{ fontSize: '18px', width: 64, height: 88 }}
          />

          <Dropdown menu={{ items: userMenuItems }} trigger={['click']}>
            <div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center' }}>
              <Space>
                <Avatar icon={<UserOutlined />} style={{ backgroundColor: '#1677ff' }} />
                {!isMobile && (
                  <span style={{ fontWeight: '500', fontSize: '14px' }}>
                    {userData?.username || 'Người dùng'}
                  </span>
                )}
                <DownOutlined style={{ fontSize: '10px', color: '#8c8c8c' }} />
              </Space>
            </div>
          </Dropdown>
        </Header>

        <Content
          style={{
            margin: isMobile ? '12px 8px' : '24px 16px',
            padding: isMobile ? 12 : 24,
            minHeight: 280,
            background: colorBgContainer,
            borderRadius: borderRadiusLG,
            overflow: 'auto'
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;