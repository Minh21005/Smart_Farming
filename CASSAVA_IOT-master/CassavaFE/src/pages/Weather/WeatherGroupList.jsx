import React, { useEffect, useState } from 'react';
import { Card, List, Typography, Button, Spin, Empty, Tag, Space, message } from 'antd';
import { ApartmentOutlined, LoadingOutlined, RightOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import groupService from '../../services/groupService';
import fieldService from '../../services/fieldService';

const { Title, Text } = Typography;

const WeatherGroupList = () => {
  const navigate = useNavigate();
  const [groups, setGroups] = useState([]);
  const [fieldCountByGroup, setFieldCountByGroup] = useState({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const [gRes, fRes] = await Promise.all([
          groupService.get(''),
          fieldService.get('/field'),
        ]);
        if (cancelled) return;
        setGroups(gRes.data || []);
        const counts = {};
        (fRes.data || []).forEach((f) => {
          if (!f.groupId) return;
          counts[f.groupId] = (counts[f.groupId] || 0) + 1;
        });
        setFieldCountByGroup(counts);
      } catch (err) {
        console.error('Lỗi tải danh sách nhóm:', err);
        message.error('Không thể tải danh sách nhóm cánh đồng.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => { cancelled = true; };
  }, []);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} />
        <div style={{ marginTop: 16 }}>Đang tải danh sách nhóm cánh đồng...</div>
      </div>
    );
  }

  return (
    <div style={{ padding: '24px' }}>
      <Card
        title={<Title level={3} style={{ margin: 0 }}>Trạm thời tiết - Chọn nhóm cánh đồng</Title>}
      >
        <Text type="secondary">
          Mỗi nhóm sở hữu một trạm thời tiết dùng chung cho mọi cánh đồng trong nhóm
          (nhiệt độ, độ ẩm không khí, mưa, bức xạ, gió).
        </Text>

        {groups.length === 0 ? (
          <Empty description="Chưa có nhóm cánh đồng nào." style={{ marginTop: 24 }} />
        ) : (
          <List
            style={{ marginTop: 16 }}
            itemLayout="horizontal"
            dataSource={groups}
            renderItem={(g) => (
              <List.Item
                style={{ padding: '16px 0' }}
                actions={[
                  <Button
                    type="primary"
                    ghost
                    onClick={() => navigate(`/weather/${g.id}`)}
                  >
                    Xem trạm thời tiết <RightOutlined />
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  avatar={
                    <div style={{
                      fontSize: 24,
                      background: '#f5f5f5',
                      padding: 12,
                      borderRadius: 8,
                      display: 'flex',
                      alignItems: 'center',
                    }}>
                      <ApartmentOutlined style={{ color: '#1890ff' }} />
                    </div>
                  }
                  title={
                    <Space>
                      <Text strong style={{ fontSize: 16 }}>{g.name}</Text>
                      <Tag color="geekblue">{fieldCountByGroup[g.id] || 0} cánh đồng</Tag>
                    </Space>
                  }
                  description={
                    <Text type="secondary">
                      {g.createdAt
                        ? `Tạo ngày: ${new Date(g.createdAt).toLocaleDateString('vi-VN')}`
                        : 'Nhóm cánh đồng dùng chung trạm thời tiết'}
                    </Text>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Card>
    </div>
  );
};

export default WeatherGroupList;
