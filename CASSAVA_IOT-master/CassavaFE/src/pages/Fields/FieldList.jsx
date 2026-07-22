
import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Typography, Popconfirm, message, Card, Tag, Modal, Input, DatePicker } from 'antd';
import dayjs from 'dayjs';
import { 
  PlusOutlined, 
  EditOutlined, 
  DeleteOutlined, 
  EyeOutlined,
  CopyOutlined,
  ReloadOutlined,
  CloudOutlined
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';

// 1. Import file cấu hình Axios của cậu (Nhớ trỏ đúng đường dẫn)
import fieldService from '../../services/fieldService';
import groupService from '../../services/groupService';
//import { fieldService } from '../../services/fieldService';
import FieldModal from './components/FieldModal';

const { Title } = Typography;

const FieldList = () => {
  const userData = JSON.parse(localStorage.getItem('user'));
  const isAdmin = userData?.isAdmin === true;
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const currentPage = Number(searchParams.get('page')) || 1;

  // --- CÁC STATE QUẢN LÝ DỮ LIỆU VÀ GIAO DIỆN ---
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingField, setEditingField] = useState(null);
  
  // State mới: Dùng để lưu danh sách từ API
  const [fields, setFields] = useState([]);
  const [groupNameById, setGroupNameById] = useState({});

  // State mới: Hiển thị vòng xoay loading trong lúc đợi API trả dữ liệu
  const [loading, setLoading] = useState(false);

  // state clone
  const [cloneModalVisible, setCloneModalVisible] = useState(false);
  const [sourceFieldId, setSourceFieldId] = useState('');
  const [newFieldId, setNewFieldId] = useState('');

  // state reset crop
  const [resetCropModalVisible, setResetCropModalVisible] = useState(false);
  const [resetCropField, setResetCropField] = useState(null);
  const [resetCropStartTime, setResetCropStartTime] = useState(dayjs());
  const [resetCropEndTime, setResetCropEndTime] = useState(null);

  // --- GỌI API LẤY DỮ LIỆU (USE EFFECT) ---
  const fetchFields = async () => {
    setLoading(true);
    try {
      const [fRes, gRes] = await Promise.all([
        fieldService.get('/field'),
        groupService.get(''),
      ]);
      setFields(fRes.data || []);
      const map = {};
      (gRes.data || []).forEach((g) => { map[g.id] = g.name; });
      setGroupNameById(map);
    } catch (error) {
      console.error("Lỗi khi tải danh sách cánh đồng:", error);
      message.error("Không thể tải dữ liệu từ máy chủ!");
    } finally {
      setLoading(false);
    }
  };

  // Tự động chạy hàm fetchFields khi vào trang
  useEffect(() => {
    fetchFields();
  }, []);

  // --- CÁC HÀM XỬ LÝ SỰ KIỆN NÚT BẤM ---
  const handleViewDetail = (id) => {
    navigate(`/fields/${id}`);
  };

  const handleAddNew = () => {
    setEditingField(null); 
    setIsModalOpen(true);  
  };

  const handleEditParams = (record) => {
    setEditingField(record); 
    setIsModalOpen(true);    
  };

  const handleModalSubmit = async (values) => {
     setLoading(true);
     try {
      const payload = {
        ...values,
        idUser: editingField?.idUser || userData?.id,
      };
      if(editingField) {
        const fieldId = editingField.id;
        await fieldService.put(`/field/updateField/${fieldId}`, payload);
        message.success("Cập nhật thông số cánh đồng thành công!");
      } else {
        await fieldService.post('/field/createField', payload);
        message.success("Thêm cánh đồng mới thành công!");
      }
      setIsModalOpen(false); // Đóng Modal
      fetchFields();         // Load lại danh sách mới từ Server
     } catch(error) {
      console.error("Lỗi khi lưu dữ liệu:", error);
      message.error("Không thể lưu dữ liệu. Vui lòng kiểm tra lại!");
     } finally {
      setLoading(false);
    }
  /**   message.success("Chức năng lưu xuống DB đang được phát triển!");
    setIsModalOpen(false);
    fetchFields(); // Load lại bảng sau khi lưu
    */
  };
 /**   const handleRefresh = async (id) => {
    setLoading(true);
    try {
      // Gọi API refresh
      await fieldService.put(`/field/${id}/refresh`);
      message.success(`Cánh đồng ${id} đã được làm mới cho vụ vụ mới!`);
      fetchFields(); // Tải lại danh sách
    } catch (error) {
      console.error("Lỗi khi làm mới cánh đồng:", error);
      message.error("Không thể làm mới cánh đồng!");
    } finally {
      setLoading(false);
    }
  };
  */
  const handleDelete = async (id) => {
    if (!isAdmin) {
      return message.error('Lỗi: Bạn không có quyền thực hiện hành động này!');
    }
    try {
      await fieldService.delete(`/field/delete/${id}`);
      setFields((prev) => prev.filter((item) => item.id !== id));
      message.success('Đã xóa cánh đồng thành công!');
    } catch (error) {
      console.error('Delete field error:', error);
      const serverMsg =
        (typeof error.response?.data === 'string' && error.response.data) ||
        error.response?.data?.message ||
        error.message;
      message.error(`Không xoá được cánh đồng: ${serverMsg || 'lỗi không xác định'}`);
    }
  };

  const openResetCropModal = (record) => {
    if (!isAdmin) {
      return message.error('Lỗi: Bạn không có quyền thực hiện hành động này!');
    }
    setResetCropField(record);
    setResetCropStartTime(dayjs());
    setResetCropEndTime(null);
    setResetCropModalVisible(true);
  };

  const confirmResetCrop = async () => {
    if (!resetCropField) return;
    if (!resetCropStartTime) {
      return message.error('Vui lòng chọn ngày bắt đầu vụ mới!');
    }
    if (resetCropEndTime && !resetCropEndTime.isAfter(resetCropStartTime)) {
      return message.error('Ngày kết thúc phải sau ngày bắt đầu!');
    }
    setLoading(true);
    try {
      await fieldService.post(`/field/resetCrop/${resetCropField.id}`, {
        startTime: resetCropStartTime.toDate().toISOString(),
        endTime: resetCropEndTime ? resetCropEndTime.toDate().toISOString() : null,
      });
      message.success('Đã bắt đầu vụ mùa mới cho cánh đồng!');
      setResetCropModalVisible(false);
      setResetCropField(null);
      fetchFields();
    } catch (error) {
      console.error('Reset crop error:', error);
      message.error(error.response?.data || 'Không thể làm mới cánh đồng!');
    } finally {
      setLoading(false);
    }
  };

  const handleClone = (record) => {
    setSourceFieldId(record.id);
    setNewFieldId(`${record.name}_copy`);
    setCloneModalVisible(true);  // Mở popup
  };
  const confirmClone = async () => {
    if (!newFieldId.trim()) {
      return message.error("Vui lòng nhập tên cánh đồng mới!");
    }

    setLoading(true);
    try {
      // Gọi API clone: POST /api/fields/{sourceId}/clone
      // Body gửi lên là { newId: "tên mới" }
      await fieldService.post(`/field/clone/${sourceFieldId}`, {
        newName: newFieldId
      });

      message.success(`Nhân bản cánh đồng thành "${newFieldId}" thành công!`);
      setCloneModalVisible(false);
      fetchFields(); // Tải lại danh sách để thấy cánh đồng mới
    } catch (error) {
      console.error("Clone error:", error);
      // Hiển thị lỗi từ BE (ví dụ: tên đã tồn tại)
      message.error(error.response?.data || "Lỗi khi nhân bản cánh đồng!");
    } finally {
      setLoading(false);
    }
  };

  // --- CẤU HÌNH CÁC CỘT CHO BẢNG MỚI KHỚP VỚI JSON TỪ BACKEND ---
  const columns = [
    {
      title: 'Tên Cánh Đồng',
      dataIndex: 'name',
      key: 'name',
      render: (text) => <strong>{text}</strong>,
    },
    {
      title: 'Loại',
      dataIndex: 'mode',
      key: 'mode',
      render: (mode) => {
        const m = mode || 'SIMULATION';
        return m === 'OPERATION'
          ? <Tag color="volcano">Thực thi</Tag>
          : <Tag color="purple">Mô phỏng</Tag>;
      },
    },
    {
      title: 'Nhóm',
      dataIndex: 'groupId',
      key: 'groupId',
      render: (gid) => gid ? <Tag color="geekblue">{groupNameById[gid] || gid}</Tag> : <Tag>—</Tag>,
    },
  /**   {
      title: 'Diện tích (m²)',
      dataIndex: 'acreage', // Đổi từ area -> acreage
      key: 'acreage',
    }, */
    {
      title: 'Ngày bắt đầu trồng',
      dataIndex: 'startTime', // Đổi từ plantingDate -> startTime
      key: 'startTime',
      render: (text) => {
        // Format ngày giờ từ "2026-04-02T09:56:28.104+00:00" cho dễ đọc
        if (!text) return '';
        const date = new Date(text);
        return date.toLocaleDateString('vi-VN');
      }
    },
    {
      title: 'Chế độ tưới',
      dataIndex: 'autoIrrigation', // Đổi từ model -> autoIrrigation
      key: 'autoIrrigation',
      render: (isAuto) => (
        isAuto ? <Tag color="green">Tự động</Tag> : <Tag color="default">Thủ công</Tag>
      ),
    },
    {
      title: 'Trạng thái bơm',
      dataIndex: 'irrigating', // Biến boolean báo xem có đang bơm không
      key: 'irrigating',
      render: (isIrrigating) => (
        isIrrigating ? <Tag color="blue" className="animate-pulse">Đang bơm</Tag> : <Tag>Tắt</Tag>
      ),
    },
    {
      title: 'Hành động',
      key: 'action',
      align: 'center',
      render: (_, record) => (
        <Space size="small" wrap>
          <Button type="primary" icon={<EyeOutlined />} onClick={() => handleViewDetail(record.id)}>
            Chi tiết
          </Button>
          <Button
            type="primary"
            icon={<CloudOutlined />}
            onClick={() => navigate(`/fields/${record.id}/soil-sensor`)}
          >
            Xem cảm biến
          </Button>
          {isAdmin && (
            <Button icon={<EditOutlined />} onClick={() => handleEditParams(record)}>
              Sửa tham số
            </Button>
          )}
          {isAdmin && (
            <Button icon={<CopyOutlined />} onClick={() => handleClone(record)} title="Sao chép cánh đồng">
              Clone
            </Button>
          )}
          {isAdmin && (
            <Button
              icon={<ReloadOutlined />}
              style={{ color: '#52c41a', borderColor: '#52c41a' }}
              title="Bắt đầu vụ mùa mới"
              onClick={() => openResetCropModal(record)}
            >
              Mùa mới
            </Button>
          )}
          {isAdmin && (
            <Popconfirm
              title="Xóa cánh đồng"
              description={`Bạn có chắc chắn muốn xóa cánh đồng "${record.name}" không?`}
              onConfirm={() => handleDelete(record.id)}
              okText="Có, Xóa"
              cancelText="Hủy"
            >
              <Button danger icon={<DeleteOutlined />}>Xóa</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  // --- RENDER GIAO DIỆN ---
  return (
    <div style={{ padding: '16px' }}>
      <Card style={{ overflow: 'hidden' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
          <Title level={3} style={{ margin: 0 }}>Danh sách cánh đồng</Title>
          <Space>
            {isAdmin && (
              <Button type="primary" icon={<PlusOutlined />} onClick={handleAddNew}>
                Thêm cánh đồng
              </Button>
            )}
          </Space>
        </div>

        <Table
          columns={columns}
          dataSource={fields}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: currentPage,
            pageSize: 8,
            position: ['bottomCenter'],
            onChange: (page) => {
              setSearchParams({ page: String(page) });
            },
          }}
        />
      </Card>
      {/* MODAL NHẬP TÊN ĐỂ CLONE */}
      <Modal
        title="Nhân bản cánh đồng"
        open={cloneModalVisible}
        onOk={confirmClone}
        onCancel={() => setCloneModalVisible(false)}
        okText="Nhân bản ngay"
        cancelText="Hủy bỏ"
        confirmLoading={loading}
      >
        <p>Nhập tên cho cánh đồng nhân bản:</p>
        <Input
          placeholder="Ví dụ: Cánh đồng A bản sao" 
          value={newFieldId} 
          onChange={(e) => setNewFieldId(e.target.value)}
          onPressEnter={confirmClone} // Nhấn Enter để submit luôn cho tiện
        />
      </Modal>
      <FieldModal
        open={isModalOpen}
        onCancel={() => setIsModalOpen(false)}
        onSubmit={handleModalSubmit}
        initialData={editingField}
      />

      {/* MODAL BẮT ĐẦU VỤ MÙA MỚI */}
      <Modal
        title="Bắt đầu vụ mùa mới"
        open={resetCropModalVisible}
        onOk={confirmResetCrop}
        onCancel={() => setResetCropModalVisible(false)}
        okText="Có, bắt đầu vụ mới"
        cancelText="Hủy"
        okButtonProps={{ loading }}
      >
        <p style={{ marginTop: 0 }}>
          Bắt đầu một vụ mùa mới cho cánh đồng
          {resetCropField ? ` "${resetCropField.name}"` : ''}.
          Lịch sử mô phỏng và tưới của các vụ trước vẫn được <strong>giữ lại</strong> và phân biệt theo ngày bắt đầu vụ.
        </p>
        <p style={{ marginBottom: 8 }}>Ngày bắt đầu vụ mới:</p>
        <DatePicker
          style={{ width: '100%' }}
          format="DD/MM/YYYY"
          value={resetCropStartTime}
          onChange={(d) => setResetCropStartTime(d)}
          placeholder="Chọn ngày bắt đầu"
        />
        <p style={{ marginTop: 12, marginBottom: 8 }}>Ngày kết thúc vụ (tuỳ chọn):</p>
        <DatePicker
          style={{ width: '100%' }}
          format="DD/MM/YYYY"
          value={resetCropEndTime}
          onChange={(d) => setResetCropEndTime(d)}
          placeholder="Trống = vụ đang chạy (mô phỏng tới hôm nay)"
          allowClear
        />
        <p style={{ marginTop: 8, color: '#999', fontSize: 12 }}>
          Có thể chọn khoảng ngày trong quá khứ để mô phỏng lại vụ đã qua.
        </p>
      </Modal>
    </div>
  );
};

export default FieldList;