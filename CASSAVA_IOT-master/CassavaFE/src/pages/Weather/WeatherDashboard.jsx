import React, { useCallback, useEffect, useState } from 'react';
import { Card, List, Typography, Button, Tag, Space, Spin, message, Row, Col } from 'antd';
import {
  CloudOutlined,
  FireOutlined,
  ThunderboltOutlined,
  CompassOutlined,
  DashboardOutlined,
  LineChartOutlined,
  RightOutlined,
  LoadingOutlined,
  ApartmentOutlined,
  ArrowLeftOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import groupService from '../../services/groupService';
import api from '../../services/api';

const { Title, Text } = Typography;

const SENSOR_CONFIG = {
  temperature: { name: 'Nhiệt độ môi trường', unit: '°C', icon: <FireOutlined style={{ color: '#cf1322' }} /> },
  relativeHumidity: { name: 'Độ ẩm không khí', unit: '%', icon: <CloudOutlined style={{ color: '#096dd9' }} /> },
  rain: { name: 'Lượng mưa tích lũy', unit: 'mm', icon: <DashboardOutlined style={{ color: '#3f6600' }} /> },
  radiation: { name: 'Bức xạ mặt trời', unit: 'MJ/m²/h', icon: <ThunderboltOutlined style={{ color: '#d48806' }} /> },
  wind: { name: 'Tốc độ gió', unit: 'm/s', icon: <CompassOutlined style={{ color: '#531dab' }} /> },
};

// Canonical units for the "current readings" panel — match what the edge C
// binaries publish (see CLAUDE.md "Sensor units"). Different from
// SENSOR_CONFIG above which is used by the per-sensor list (kept as-is to
// avoid touching unrelated copy).
const LATEST_PANEL_LABELS = {
  temperature:      { label: 'Nhiệt độ',       unit: '°C' },
  rain:             { label: 'Lượng mưa',      unit: 'mm/h' },
  relativeHumidity: { label: 'Độ ẩm tương đối', unit: '%' },
  wind:             { label: 'Tốc độ gió',     unit: 'm/s' },
  radiation:        { label: 'Bức xạ mặt trời', unit: 'MJ/m²/h' },
};

// Order matches the screenshot: temp + rain in row 1, RH + wind in row 2, radiation alone in row 3.
const LATEST_PANEL_LAYOUT = [
  ['temperature', 'rain'],
  ['relativeHumidity', 'wind'],
  ['radiation', null],
];

const formatValue = (sensorId, value) => {
  if (value == null) return '—';
  // Match the screenshot's precision: temperature 2dp, radiation 3dp, rest 1dp.
  if (sensorId === 'temperature') return value.toFixed(2);
  if (sensorId === 'radiation') return value.toFixed(3);
  return value.toFixed(1);
};

const getSensorConfig = (sensorId) =>
  SENSOR_CONFIG[sensorId] || { name: sensorId, unit: '', icon: <DashboardOutlined /> };

const WeatherDashboard = () => {
  const navigate = useNavigate();
  const { groupId } = useParams();

  const [groupInfo, setGroupInfo] = useState(null);
  const [groupSensors, setGroupSensors] = useState([]);
  const [loading, setLoading] = useState(true);
  const [latest, setLatest] = useState(null);            // { readings, latestTime }
  const [latestLoading, setLatestLoading] = useState(false);

  const fetchLatest = useCallback(async () => {
    if (!groupId) return;
    setLatestLoading(true);
    try {
      const res = await api.get('/sensor-values/latest', { params: { groupId } });
      setLatest(res.data);
    } catch (err) {
      console.error('Lỗi tải dữ liệu mới nhất:', err);
      message.error('Không thể tải số liệu thời tiết mới nhất!');
    } finally {
      setLatestLoading(false);
    }
  }, [groupId]);

  useEffect(() => {
    if (!groupId) return;
    let cancelled = false;

    const load = async () => {
      setLoading(true);
      try {
        const [gRes, gsRes] = await Promise.all([
          groupService.get(`/${groupId}`),
          groupService.get(`/${groupId}/sensor`),
        ]);
        if (cancelled) return;
        setGroupInfo(gRes.data);
        setGroupSensors(gsRes.data || []);
      } catch (err) {
        console.error('Lỗi tải dữ liệu trạm thời tiết:', err);
        message.error('Không thể tải dữ liệu trạm thời tiết!');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    load();
    fetchLatest();
    return () => { cancelled = true; };
  }, [groupId, fetchLatest]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} />
        <div style={{ marginTop: 16 }}>Đang tải danh sách cảm biến...</div>
      </div>
    );
  }

  return (
    <div style={{ padding: '24px' }}>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/weather')}
        style={{ marginBottom: 16 }}
      >
        Quay lại danh sách nhóm
      </Button>

      <Card
        title={
          <Space wrap>
            <DashboardOutlined />
            <Title level={4} style={{ margin: 0 }}>Dữ liệu thời tiết</Title>
          </Space>
        }
        extra={
          <Space>
            {latest?.latestTime && (
              <Text type="secondary">
                Cập nhật: {new Date(latest.latestTime).toLocaleString('vi-VN')}
              </Text>
            )}
            <Button
              size="small"
              icon={<ReloadOutlined />}
              loading={latestLoading}
              onClick={fetchLatest}
            >
              Làm mới
            </Button>
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        {LATEST_PANEL_LAYOUT.map((row, ri) => (
          <Row key={ri} gutter={[24, 12]} style={{ marginBottom: ri < LATEST_PANEL_LAYOUT.length - 1 ? 12 : 0 }}>
            {row.map((sensorId, ci) => (
              <Col key={ci} xs={24} md={12}>
                {sensorId ? (
                  <Space size="large" style={{ width: '100%' }}>
                    <Text style={{ minWidth: 160, fontSize: 15 }}>
                      {LATEST_PANEL_LABELS[sensorId].label}:
                    </Text>
                    <Text strong style={{ fontSize: 16 }}>
                      {formatValue(sensorId, latest?.readings?.[sensorId]?.value)}{' '}
                      <Text type="secondary" style={{ fontSize: 14 }}>
                        {LATEST_PANEL_LABELS[sensorId].unit}
                      </Text>
                    </Text>
                  </Space>
                ) : null}
              </Col>
            ))}
          </Row>
        ))}
      </Card>

      <Card
        title={
          <Space wrap>
            <ApartmentOutlined />
            <Title level={3} style={{ margin: 0 }}>
              Trạm thời tiết: {groupInfo?.name || groupId}
            </Title>
          </Space>
        }
        extra={<Tag color="green">Đang kết nối</Tag>}
      >
        <Text type="secondary">
          5 cảm biến thời tiết dùng chung cho mọi cánh đồng trong nhóm.
        </Text>

        <List
          style={{ marginTop: 16 }}
          itemLayout="horizontal"
          dataSource={groupSensors}
          locale={{ emptyText: 'Nhóm chưa có cảm biến thời tiết.' }}
          renderItem={(item) => {
            const config = getSensorConfig(item.sensorId);
            return (
              <List.Item
                style={{ padding: '20px 0' }}
                actions={[
                  <Button
                    type="primary"
                    ghost
                    icon={<LineChartOutlined />}
                    onClick={() =>
                      navigate(`/weather/detail/${item.sensorId}?groupId=${groupId}`)
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
                      {config.icon}
                    </div>
                  }
                  title={<Text strong style={{ fontSize: '16px' }}>{config.name}</Text>}
                  description={
                    <Space direction="vertical" size={2}>
                      <Text type="secondary">ID cảm biến: {item.sensorId}</Text>
                      <Text type="secondary">Đơn vị: {config.unit || '—'}</Text>
                    </Space>
                  }
                />
              </List.Item>
            );
          }}
        />
      </Card>
    </div>
  );
};

export default WeatherDashboard;
