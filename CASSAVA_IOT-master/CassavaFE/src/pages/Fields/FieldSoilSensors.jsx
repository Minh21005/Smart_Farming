import React, { useEffect, useState } from 'react';
import { Card, List, Typography, Button, Space, Spin, message, Tag } from 'antd';
import {
  CloudOutlined,
  LineChartOutlined,
  RightOutlined,
  LoadingOutlined,
  ArrowLeftOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import fieldService from '../../services/fieldService';

const { Title, Text } = Typography;

const FieldSoilSensors = () => {
  const navigate = useNavigate();
  const { fieldId } = useParams();

  const [fieldInfo, setFieldInfo] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!fieldId) return;
    let cancelled = false;
    const load = async () => {
      setLoading(true);
      try {
        const res = await fieldService.get(`/field/${fieldId}`);
        if (!cancelled) setFieldInfo(res.data);
      } catch (err) {
        console.error('Lỗi tải thông tin cánh đồng:', err);
        message.error('Không thể tải thông tin cánh đồng!');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => { cancelled = true; };
  }, [fieldId]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} />
        <div style={{ marginTop: 16 }}>Đang tải dữ liệu cảm biến...</div>
      </div>
    );
  }

  const soilItem = {
    sensorId: 'soilHumidity',
    name: 'Độ ẩm đất',
    description: 'Biểu đồ kết hợp độ ẩm đất ở độ sâu 30cm và 60cm',
    unit: '%',
  };

  return (
    <div style={{ padding: '24px' }}>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/fields')}
        style={{ marginBottom: 16 }}
      >
        Quay lại danh sách cánh đồng
      </Button>

      <Card
        title={
          <Space wrap>
            <CloudOutlined />
            <Title level={3} style={{ margin: 0 }}>
              Cảm biến cánh đồng: {fieldInfo?.name || fieldId}
            </Title>
          </Space>
        }
        extra={<Tag color="green">Đang kết nối</Tag>}
      >
        <Text type="secondary">
          Cảm biến riêng của cánh đồng (không chia sẻ theo nhóm).
        </Text>

        <List
          style={{ marginTop: 16 }}
          itemLayout="horizontal"
          dataSource={[soilItem]}
          renderItem={(item) => (
            <List.Item
              style={{ padding: '20px 0' }}
              actions={[
                <Button
                  type="primary"
                  ghost
                  icon={<LineChartOutlined />}
                  onClick={() =>
                    navigate(`/weather/detail/${item.sensorId}?fieldId=${fieldId}`)
                  }
                >
                  Xem đồ thị <RightOutlined />
                </Button>,
              ]}
            >
              <List.Item.Meta
                avatar={
                  <div style={{
                    fontSize: '24px',
                    background: '#f5f5f5',
                    padding: '12px',
                    borderRadius: '8px',
                    display: 'flex',
                    alignItems: 'center',
                  }}>
                    <CloudOutlined style={{ color: '#13c2c2' }} />
                  </div>
                }
                title={<Text strong style={{ fontSize: '16px' }}>{item.name}</Text>}
                description={
                  <Space direction="vertical" size={2}>
                    <Text type="secondary">{item.description}</Text>
                    <Text type="secondary">Đơn vị: {item.unit}</Text>
                  </Space>
                }
              />
            </List.Item>
          )}
        />
      </Card>
    </div>
  );
};

export default FieldSoilSensors;
