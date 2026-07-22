import React, { useEffect, useMemo, useState } from 'react';
import {
  Table, Button, Space, Typography, Popconfirm, message, Card, Tag, Drawer, List, Empty,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, ApartmentOutlined, CloudOutlined,
} from '@ant-design/icons';
import groupService from '../../services/groupService';
import fieldService from '../../services/fieldService';
import FieldGroupModal from './components/FieldGroupModal';

const { Title, Text } = Typography;

// Mirror of FieldGroupService.GROUP_SENSOR_IDS
const SENSOR_LABEL = {
  temperature: 'Nhiệt độ',
  relativeHumidity: 'Độ ẩm không khí',
  rain: 'Lượng mưa',
  radiation: 'Bức xạ',
  wind: 'Tốc độ gió',
};

const FieldGroupList = () => {
  const userData = JSON.parse(localStorage.getItem('user'));
  const isAdmin = userData?.isAdmin === true;
  const userId = userData?.id || userData?._id || userData?.userId || '';

  const [groups, setGroups] = useState([]);
  const [fields, setFields] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerGroup, setDrawerGroup] = useState(null);
  const [drawerSensors, setDrawerSensors] = useState([]);
  const [drawerLoading, setDrawerLoading] = useState(false);

  const fieldsByGroup = useMemo(() => {
    const m = new Map();
    fields.forEach((f) => {
      if (!f.groupId) return;
      if (!m.has(f.groupId)) m.set(f.groupId, []);
      m.get(f.groupId).push(f);
    });
    return m;
  }, [fields]);

  const fetchAll = async () => {
    setLoading(true);
    try {
      const [gRes, fRes] = await Promise.all([
        groupService.get(''),
        fieldService.get('/field'),
      ]);
      setGroups(gRes.data || []);
      setFields(fRes.data || []);
    } catch (err) {
      console.error('Lỗi tải danh sách nhóm:', err);
      message.error('Không thể tải danh sách nhóm cánh đồng!');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAll();
  }, []);

  const handleAdd = () => {
    setEditing(null);
    setModalOpen(true);
  };

  const handleEdit = (record) => {
    setEditing(record);
    setModalOpen(true);
  };

  const handleSubmit = async (values) => {
    setLoading(true);
    try {
      if (editing) {
        await groupService.put(`/${editing.id}`, { ...editing, ...values });
        message.success('Cập nhật nhóm thành công!');
      } else {
        await groupService.post('', { ...values, idUser: userId });
        message.success('Thêm nhóm mới thành công!');
      }
      setModalOpen(false);
      fetchAll();
    } catch (err) {
      console.error('Lỗi lưu nhóm:', err);
      message.error(err.response?.data || 'Không thể lưu nhóm!');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (id) => {
    try {
      await groupService.delete(`/${id}`);
      message.success('Đã xóa nhóm!');
      fetchAll();
    } catch (err) {
      console.error('Lỗi xóa nhóm:', err);
      message.error(err.response?.data || 'Không thể xóa nhóm (có thể còn cánh đồng đang dùng).');
    }
  };

  const openSensorsDrawer = async (group) => {
    setDrawerGroup(group);
    setDrawerOpen(true);
    setDrawerLoading(true);
    try {
      const res = await groupService.get(`/${group.id}/sensor`);
      setDrawerSensors(res.data || []);
    } catch (err) {
      console.error('Lỗi tải cảm biến của nhóm:', err);
      message.error('Không thể tải danh sách cảm biến của nhóm.');
      setDrawerSensors([]);
    } finally {
      setDrawerLoading(false);
    }
  };

  const columns = [
    {
      title: 'Tên nhóm',
      dataIndex: 'name',
      key: 'name',
      render: (text) => <strong>{text}</strong>,
    },
    {
      title: 'Số cánh đồng',
      key: 'fieldCount',
      render: (_, record) => (
        <Tag color="blue">{(fieldsByGroup.get(record.id) || []).length}</Tag>
      ),
    },
    {
      title: 'Cánh đồng thuộc nhóm',
      key: 'fields',
      render: (_, record) => {
        const list = fieldsByGroup.get(record.id) || [];
        if (list.length === 0) return <Text type="secondary">—</Text>;
        return (
          <Space size={[4, 4]} wrap>
            {list.slice(0, 4).map((f) => (
              <Tag key={f.id}>{f.name}</Tag>
            ))}
            {list.length > 4 && <Tag>+{list.length - 4}</Tag>}
          </Space>
        );
      },
    },
    {
      title: 'Ngày tạo',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (text) => (text ? new Date(text).toLocaleDateString('vi-VN') : '—'),
    },
    {
      title: 'Hành động',
      key: 'action',
      align: 'center',
      render: (_, record) => (
        <Space size="small" wrap>
          <Button icon={<CloudOutlined />} onClick={() => openSensorsDrawer(record)}>
            Cảm biến
          </Button>
          {isAdmin && (
            <Button icon={<EditOutlined />} onClick={() => handleEdit(record)}>
              Sửa
            </Button>
          )}
          {isAdmin && (
            <Popconfirm
              title="Xóa nhóm"
              description={`Xóa nhóm "${record.name}"? Phải gỡ hết cánh đồng khỏi nhóm trước.`}
              onConfirm={() => handleDelete(record.id)}
              okText="Xóa"
              cancelText="Hủy"
              okButtonProps={{ danger: true }}
            >
              <Button danger icon={<DeleteOutlined />}>Xóa</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '16px' }}>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
          <Title level={3} style={{ margin: 0 }}>
            <ApartmentOutlined style={{ marginRight: 8 }} />
            Nhóm cánh đồng
          </Title>
          {isAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
              Thêm nhóm
            </Button>
          )}
        </div>

        <Text type="secondary">
          Mỗi nhóm sở hữu 1 trạm thời tiết với 5 cảm biến chung (nhiệt độ, độ ẩm không khí, mưa, bức xạ, gió).
          Các cánh đồng trong cùng nhóm dùng chung dữ liệu thời tiết này.
        </Text>

        <Table
          style={{ marginTop: 16 }}
          columns={columns}
          dataSource={groups}
          rowKey="id"
          loading={loading}
          scroll={{ x: 'max-content' }}
          pagination={{ pageSize: 10, position: ['bottomCenter'] }}
        />
      </Card>

      <FieldGroupModal
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onSubmit={handleSubmit}
        initialData={editing}
      />

      <Drawer
        title={drawerGroup ? `Cảm biến của nhóm: ${drawerGroup.name}` : 'Cảm biến của nhóm'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={420}
      >
        {drawerLoading ? (
          <Text>Đang tải...</Text>
        ) : drawerSensors.length === 0 ? (
          <Empty description="Nhóm này chưa có cảm biến nào" />
        ) : (
          <List
            dataSource={drawerSensors}
            renderItem={(s) => (
              <List.Item>
                <List.Item.Meta
                  title={SENSOR_LABEL[s.sensorId] || s.sensorId}
                  description={<Text type="secondary">ID: {s.sensorId}</Text>}
                />
              </List.Item>
            )}
          />
        )}
      </Drawer>
    </div>
  );
};

export default FieldGroupList;
