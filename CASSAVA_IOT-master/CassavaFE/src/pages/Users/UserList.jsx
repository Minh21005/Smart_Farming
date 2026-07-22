/**import React, { useState, useEffect } from 'react';
import { Table, Input, Card, Typography, Tag, Space } from 'antd';
import { SearchOutlined, UserOutlined } from '@ant-design/icons';

const { Title } = Typography;

const UserList = () => {
  // 1. Dữ liệu mẫu (Sau này cậu sẽ gọi API từ Backend)
  const initialData = [
    { id: '1', username: 'Nam Nguyễn', email: 'nam@gmail.com', createdAt: '2026-01-10', role: 'Viewer' },
    { id: '2', username: 'Lan Anh', email: 'lananh@yahoo.com', createdAt: '2026-02-15', role: 'Viewer' },
    { id: '3', username: 'Minh Tú', email: 'tu.minh@outlook.com', createdAt: '2026-03-01', role: 'Admin' },
    { id: '4', username: 'Hoàng Nam', email: 'hnam99@gmail.com', createdAt: '2026-03-10', role: 'Viewer' },
    { id: '5', username: 'Phương Thảo', email: 'thao.p@gmail.com', createdAt: '2026-03-20', role: 'Viewer' },
  ];

  const [users, setUsers] = useState(initialData);
  const [searchText, setSearchText] = useState('');

  // 2. Hàm xử lý tìm kiếm (vd: nhập "am" sẽ ra "Nam Nguyễn" và "Hoàng Nam")
  const handleSearch = (value) => {
    setSearchText(value);
    const filteredData = initialData.filter((user) =>
      user.username.toLowerCase().includes(value.toLowerCase())
    );
    setUsers(filteredData);
  };

  // 3. Cấu hình các cột của bảng
  const columns = [
    {
      title: 'Tên người dùng',
      dataIndex: 'username',
      key: 'username',
      render: (text) => (
        <Space>
          <UserOutlined />
          <strong>{text}</strong>
        </Space>
      ),
    },
    {
      title: 'Email',
      dataIndex: 'email',
      key: 'email',
    },
    {
      title: 'Ngày tạo tài khoản',
      dataIndex: 'createdAt',
      key: 'createdAt',
      sorter: (a, b) => new Date(a.createdAt) - new Date(b.createdAt),
    },
    {
      title: 'Vai trò',
      dataIndex: 'role',
      key: 'role',
      render: (role) => (
        <Tag color={role === 'Admin' ? 'gold' : 'blue'}>
          {role.toUpperCase()}
        </Tag>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <Title level={3} style={{ margin: 0 }}>Danh sách người xem</Title>
          
          {/* Ô Search theo tên *
          <Input.Search
            placeholder="Tìm kiếm"
            allowClear
            enterButton="Tìm kiếm"
            size="large"
            onSearch={handleSearch}
            onChange={(e) => handleSearch(e.target.value)} // Tìm kiếm ngay khi đang gõ
            style={{ width: 400 }}
            prefix={<SearchOutlined />}
          />
        </div>

        <Table
          columns={columns}
          dataSource={users}
          rowKey="id"
          pagination={{ 
            pageSize: 8,
            position:['bottomCenter']
        }}
          bordered
        />
      </Card>
    </div>
  );
};

export default UserList;
*/
import React, { useState, useEffect } from 'react';
import { Table, Input, Card, Typography, Tag, Space, message } from 'antd';
import { SearchOutlined, UserOutlined } from '@ant-design/icons';
import api from '../../services/api';
const { Title } = Typography;

const UserList = () => {
  // 1. Khai báo state
  const [users, setUsers] = useState([]); // Chứa dữ liệu hiển thị trên bảng
  const [allUsers, setAllUsers] = useState([]); // Chứa toàn bộ dữ liệu gốc để phục vụ tính năng tìm kiếm (lọc)
  const [loading, setLoading] = useState(false); // Trạng thái loading khi đang gọi API
  const [searchText, setSearchText] = useState('');

  // 2. Gọi API khi Component vừa mount
  useEffect(() => {
    fetchUsers();
  }, []);

 const fetchUsers = async () => {
  setLoading(true);
  try {
    // Gọi API bằng Axios
    const response = await api.get('/api/auth/list'); 
    
    // Axios tự động chuyển JSON thành Object và lưu vào response.data
    const data = response.data; 
    
    setAllUsers(data); // Lưu bản gốc
    setUsers(data);    // Đưa vào bảng
  } catch (error) {
    console.error('Lỗi khi fetch data:', error);
    // In ra lỗi chi tiết từ Backend nếu có (để dễ debug hơn)
    const errorMessage = error.response?.data?.message || 'Không thể tải danh sách người dùng!';
    message.error(errorMessage);
  } finally {
    setLoading(false);
  }
};

  // 3. Hàm xử lý tìm kiếm trên dữ liệu đã fetch
  const handleSearch = (value) => {
    setSearchText(value);
    const filteredData = allUsers.filter((user) => {
      // Xử lý trường hợp username bị null từ BE để tránh lỗi .toLowerCase()
      const name = user.username || ""; 
      return name.toLowerCase().includes(value.toLowerCase());
    });
    setUsers(filteredData);
  };

  // 4. Cấu hình các cột của bảng (Khớp với dữ liệu BE trả về)
  const columns = [
    {
      title: 'Tên người dùng',
      dataIndex: 'username',
      key: 'username',
      render: (text) => (
        <Space>
          <UserOutlined />
          {/* Nếu text là null, hiển thị 'Chưa cập nhật' */}
          <strong>{text || <span style={{ color: '#ccc' }}>Chưa cập nhật</span>}</strong>
        </Space>
      ),
    },
    {
      title: 'Email',
      dataIndex: 'email',
      key: 'email',
      // Nếu email là null, hiển thị text mờ
      render: (text) => text || <span style={{ color: '#ccc' }}>Chưa cập nhật</span>
    },
    {
      title: 'Vai trò',
      dataIndex: 'admin', // Trỏ thẳng vào trường boolean 'admin'
      key: 'admin',
      render: (isAdmin) => (
        // Dựa vào true/false để render màu và chữ
        <Tag color={isAdmin ? 'gold' : 'blue'}>
          {isAdmin ? 'ADMIN' : 'VIEWER'}
        </Tag>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <Title level={3} style={{ margin: 0 }}>Danh sách người dùng</Title>
          
          <Input.Search
            placeholder="Tìm kiếm theo tên..."
            allowClear
            enterButton="Tìm kiếm"
            size="large"
            onSearch={handleSearch}
            onChange={(e) => handleSearch(e.target.value)} 
            style={{ width: 400 }}
            prefix={<SearchOutlined />}
          />
        </div>

        <Table
          columns={columns}
          dataSource={users}
          rowKey="id" // Báo cho Ant Design biết trường 'id' là khóa chính
          loading={loading} // Hiển thị icon xoay xoay khi đang lấy dữ liệu
          pagination={{ 
            pageSize: 8,
            position: ['bottomCenter']
          }}
          bordered
        />
      </Card>
    </div>
  );
};

export default UserList;