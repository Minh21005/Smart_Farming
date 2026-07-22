import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';

// 1. Import các trang Xác thực (Auth)
import Login from './pages/Auth/Login';
import Register from './pages/Auth/Register';

// 2. Import Layout chính (Có chứa Sidebar và Header)
import MainLayout from './components/Layout/MainLayout';

// 3. Import các trang Quản lý cánh đồng
import FieldList from './pages/Fields/FieldList';
// Dòng dưới đây sẽ tự động tìm và gọi file src/pages/Fields/FieldDetail/index.jsx
import FieldDetail from './pages/Fields/FieldDetail';

import WeatherDashboard from './pages/Weather/WeatherDashboard';
import WeatherDetail from "./pages/Weather/WeatherDetail";
import WeatherGroupList from './pages/Weather/WeatherGroupList';
import FieldSoilSensors from './pages/Fields/FieldSoilSensors';
// Import trang quản lý nhóm cánh đồng
import FieldGroupList from './pages/FieldGroups/FieldGroupList';
// Import trang danh sách người dùng
import UserList from './pages/Users/UserList';

function App() {
  return (
    <BrowserRouter basename={import.meta.env.BASE_URL}>
      <Routes>
        {/* === NHÓM ROUTE ĐỘC LẬP (Không có Sidebar) === */}
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />

        {/* === NHÓM ROUTE ĐƯỢC BẢO VỆ (Nằm trong MainLayout) === */}
        <Route element={<MainLayout />}>
          
          {/* Điều hướng mặc định: Vào localhost:3000/ -> đẩy sang /fields */}
          <Route path="/" element={<Navigate to="/login" replace />} />
          
          {/* Trang hiển thị danh sách cánh đồng (Có bảng, nút thêm/sửa/xóa) */}
          <Route path="/fields" element={<FieldList />} />

          {/* Trang hiển thị chi tiết 1 cánh đồng (Có chứa 4 Tab chức năng) */}
          <Route path="/fields/:id" element={<FieldDetail />} />

          {/* Trang quản lý nhóm cánh đồng (chia sẻ trạm thời tiết) */}
          <Route path="/field-groups" element={<FieldGroupList />} />

          {/* Trạm thời tiết theo nhóm cánh đồng */}
          <Route path="/weather" element={<WeatherGroupList />} />
          <Route path="/weather/:groupId" element={<WeatherDashboard />} />
          <Route path="weather/detail/:sensorId" element={<WeatherDetail />} />

          {/* Cảm biến đất riêng của từng cánh đồng */}
          <Route path="/fields/:fieldId/soil-sensor" element={<FieldSoilSensors />} />
          {/*đường dẫn đến trang danh sách user*/}
          <Route path="/users" element={<UserList />} />
          
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

export default App;