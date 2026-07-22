// src/pages/Fields/FieldDetail/IrrigationTab.jsx
import React, { useEffect, useState } from 'react';
import { Card, Typography, Spin, Empty, DatePicker, Space, message } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import api from '../../../services/api';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

const SOIL_SERIES = [
  { id: 'humidity30', label: 'Độ ẩm đất 30cm', color: '#13c2c2' },
  { id: 'humidity60', label: 'Độ ẩm đất 60cm', color: '#08979c' },
];

const formatTime = (iso) =>
  new Date(iso).toLocaleTimeString('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Ho_Chi_Minh',
  });

const formatFullTime = (iso) =>
  new Date(iso).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' });

const IrrigationTab = ({ fieldId }) => {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [dateRange, setDateRange] = useState(null);

  useEffect(() => {
    if (!fieldId) return;
    let cancelled = false;

    const resolveRange = (latest) => {
      if (dateRange && dateRange[0] && dateRange[1]) {
        return {
          startCutoff: dateRange[0].startOf('day').valueOf(),
          endCutoff: dateRange[1].endOf('day').valueOf(),
        };
      }
      return {
        startCutoff: latest > 0 ? latest - 24 * 60 * 60 * 1000 : 0,
        endCutoff: latest,
      };
    };

    const fetchSoilHumidity = async () => {
      setLoading(true);
      try {
        const responses = await Promise.all(
          SOIL_SERIES.map((s) =>
            api.get('/sensor-values/history', {
              params: { fieldId, sensorId: s.id },
            })
          )
        );

        const latest = Math.max(
          ...responses.flatMap((r) =>
            r.data?.[0] ? [new Date(r.data[0].time).getTime()] : []
          ),
          0
        );
        const { startCutoff, endCutoff } = resolveRange(latest);

        const buckets = new Map();
        responses.forEach((res, idx) => {
          const key = SOIL_SERIES[idx].id;
          (res.data || []).forEach((item) => {
            const t = new Date(item.time).getTime();
            if (t < startCutoff || t > endCutoff) return;
            if (!buckets.has(t)) {
              buckets.set(t, {
                t,
                time: formatTime(item.time),
                fullTime: formatFullTime(item.time),
              });
            }
            buckets.get(t)[key] = item.value;
          });
        });

        const merged = Array.from(buckets.values()).sort((a, b) => a.t - b.t);
        if (!cancelled) setData(merged);
      } catch (err) {
        console.error('Lỗi tải dữ liệu độ ẩm đất:', err);
        if (!cancelled) message.error('Không thể tải dữ liệu độ ẩm đất.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    fetchSoilHumidity();
    return () => { cancelled = true; };
  }, [fieldId, dateRange]);

  return (
    <div style={{ padding: '20px 0' }}>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'flex-end' }}>
        <Text strong>Chọn thời gian:</Text>
        <RangePicker
          value={dateRange}
          onChange={(dates) => setDateRange(dates)}
          format="DD/MM/YYYY"
          placeholder={['Từ ngày', 'Đến ngày']}
          allowClear
        />
      </Space>
      <Card
        title={<Title level={4} style={{ margin: 0 }}>Biểu đồ trực quan: Độ ẩm đất (30cm & 60cm)</Title>}
        extra={<Text type="secondary">{dateRange ? 'Dữ liệu tùy chỉnh' : 'Dữ liệu 24h qua'} • Nguồn: cánh đồng</Text>}
      >
        {loading ? (
          <div style={{ height: 400, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Spin indicator={<LoadingOutlined style={{ fontSize: 40 }} spin />} />
          </div>
        ) : data.length === 0 ? (
          <Empty description="Không có dữ liệu lịch sử cho cảm biến này" />
        ) : (
          <div style={{ width: '100%', height: 400 }}>
            <ResponsiveContainer>
              <LineChart data={data} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="time" />
                <YAxis unit="%" domain={[0, 100]} />
                <Tooltip labelFormatter={(_, payload) => payload[0]?.payload?.fullTime} />
                <Legend />
                {SOIL_SERIES.map((s) => (
                  <Line
                    key={s.id}
                    type="monotone"
                    dataKey={s.id}
                    name={s.label}
                    stroke={s.color}
                    dot={false}
                    connectNulls
                    isAnimationActive={false}
                  />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
      </Card>
    </div>
  );
};

export default IrrigationTab;
