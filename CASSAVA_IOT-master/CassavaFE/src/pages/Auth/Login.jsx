import React from 'react';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, Link } from 'react-router-dom';
import api from '../../services/api';
const { Title } = Typography;

const Login = () => {
  const navigate = useNavigate();

  // Hàm này chỉ chạy khi người dùng nhập HỢP LỆ tất cả các trường
  const onFinish = async (values) => {
  try {
    // Gọi API: POST http://localhost:8000/v1/auth/login
    const res = await api.post('api/auth/login', {
      username: values.username,
      password: values.password
    });
    
    // Lưu thông tin user hoặc giả lập token vào localStorage [cite: 49]
    localStorage.setItem('user', JSON.stringify(res.data));
    
    message.success('Đăng nhập thành công!');
    navigate('/fields'); // Chuyển hướng đến danh sách cánh đồng [cite: 5]
  } catch (error) {
    // BE trả về lỗi 404 nếu sai username/password
    message.error('Sai tài khoản hoặc mật khẩu!');
  }
};
/**const onFinish = async (values) => {
  try {
   const res = await api.post('/auth/login', {
      username: values.username,
      password: values.password
    });

    // BE trả về Object, Axios sẽ đưa vào res.data
    const data = res.data;

    if (data.success) {
      // 1. Lưu thông tin vào localStorage để api.js (interceptor) lấy ra dùng
      localStorage.setItem('user', JSON.stringify(data));
      
      // 2. Thông báo và chuyển hướng
      message.success(`Chào mừng ${data.username} quay trở lại!`);
      navigate('/fields'); 
    } else {
      // Trường hợp BE trả về 200 OK nhưng success: false (nếu có logic này)
      message.error(data.message || 'Đăng nhập thất bại!');
    } 
   navigate('/fields');
  } catch (error) {
    console.error("Login Error:", error);
    message.error('Tài khoản hoặc mật khẩu không chính xác!');
  }
}; */
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400, boxShadow: '0 4px 12px rgba(0,0,0,0.1)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Title level={3}>Smart Farming</Title>
          <p>Đăng nhập hệ thống theo dõi</p>
        </div>

        <Form
          name="login_form"
          layout="vertical"
          onFinish={onFinish}
        >
          <Form.Item
            name="username"
            rules={[{ required: true, message: 'Vui lòng nhập tên tài khoản!' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="Tên tài khoản" size="large" />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[{ required: true, message: 'Vui lòng nhập mật khẩu!' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="Mật khẩu" size="large" />
          </Form.Item>

          <Form.Item>
            <Button type="primary" htmlType="submit" size="large" block>
              Đăng nhập
            </Button>
          </Form.Item>

          <div style={{ textAlign: 'center' }}>
            Chưa có tài khoản? <Link to="/register">Đăng ký ngay</Link>
          </div>
        </Form>
      </Card>
    </div>
  );
};

export default Login;