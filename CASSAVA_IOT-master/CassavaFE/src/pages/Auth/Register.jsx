import React from 'react';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, Link } from 'react-router-dom';
import api from '../../services/api';
const { Title } = Typography;

const Register = () => {
  const navigate = useNavigate();

  const onFinish = async (values) => {
  try {
    // Gọi API: POST http://localhost:8000/v1/auth/register
    await api.post('api/auth/register', {
      username: values.username,
      email: values.email, // Cần thêm trường này vào Form Ant Design
      password: values.password
    });
    message.success('Đăng ký thành công!');
    navigate('/login');
  } catch (error) {
    message.error('Đăng ký thất bại. Vui lòng kiểm tra lại thông tin.');
  }
};

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400, boxShadow: '0 4px 12px rgba(0,0,0,0.1)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Title level={3}>Đăng Ký Tài Khoản</Title>
          <p>Tham gia hệ thống Smart Farming</p>
        </div>

        <Form
          name="register_form"
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
            name="email"
            rules={[{ required: true, message: 'Vui lòng nhập tên email!' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="Tên email" size="large" />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: 'Vui lòng nhập mật khẩu!' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="Mật khẩu" size="large" />
          </Form.Item>

          <Form.Item
            name="confirmPassword"
            dependencies={['password']}
            hasFeedback
            rules={[
              { required: true, message: 'Vui lòng xác nhận mật khẩu!' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('password') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('Mật khẩu xác nhận không khớp!'));
                },
              }),
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="Xác nhận mật khẩu" size="large" />
          </Form.Item>

          <Form.Item>
            <Button type="primary" htmlType="submit" size="large" block>
              Đăng ký
            </Button>
          </Form.Item>

          <div style={{ textAlign: 'center' }}>
            Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
          </div>
        </Form>
      </Card>
    </div>
  );
};

export default Register;