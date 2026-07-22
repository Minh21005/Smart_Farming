import React, { useEffect, useState } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { Card, Button, Typography, Spin, message, Empty, DatePicker, Space } from 'antd';
import { ArrowLeftOutlined, LoadingOutlined } from '@ant-design/icons';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  ComposedChart,
  Area,
  Brush,
} from 'recharts';
import api from '../../services/api';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

const SOIL_SERIES = [
  { id: 'humidity30', label: 'Độ ẩm đất 30cm', color: '#13c2c2' },
  { id: 'humidity60', label: 'Độ ẩm đất 60cm', color: '#08979c' },
];

const SENSOR_NAMES_VI = {
  temperature: 'Nhiệt độ môi trường',
  relativeHumidity: 'Độ ẩm không khí',
  humidity30: 'Độ ẩm đất 30cm',
  humidity60: 'Độ ẩm đất 60cm',
  soilHumidity: 'Độ ẩm đất (30cm & 60cm)',
  rain: 'Lượng mưa tích lũy',
  rainfall: 'Lượng mưa tích lũy',
  radiation: 'Bức xạ mặt trời',
  wind: 'Tốc độ gió',
  wind_speed: 'Tốc độ gió',
};

const getChartColor = (id) => {
  switch (id) {
    case 'temperature': return '#ff4d4f';
    case 'relativeHumidity': return '#1890ff';
    case 'humidity30': return '#13c2c2';
    case 'humidity60': return '#08979c';
    case 'rainfall':
    case 'rain': return '#52c41a';
    default: return '#722ed1';
  }
};

// Intl.DateTimeFormat chèn ký tự narrow no-break space (U+202F) giữa giờ và ngày trên một số runtime,
// gộp mọi whitespace về space thường để tránh lệch render giữa browser.
const formatTime = (iso) => {
  const date = new Date(iso);
  return new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    day: '2-digit',
    month: '2-digit',
    timeZone: 'Asia/Ho_Chi_Minh',
  }).format(date).replace(/\s+/g, ' ');
};

const formatFullTime = (iso) => new Date(iso).toLocaleString('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
});

const fmtNum = (v) =>
  v === null || v === undefined ? '–' : typeof v === 'number' ? Number(v.toFixed(2)) : v;

// Custom tooltip: shows the real value always; at an anomalous point it also
// surfaces the forecaster's predicted value + which methods detected/predicted.
const ChartTooltip = ({ active, payload }) => {
  if (!active || !payload || !payload.length) return null;
  const p = payload[0]?.payload || {};
  const isAnomaly = p.anomalyValue !== null && p.anomalyValue !== undefined;
  return (
    <div style={{ background: '#fff', border: '1px solid #d9d9d9', borderRadius: 6, padding: '8px 12px', fontSize: 13, boxShadow: '0 2px 8px rgba(0,0,0,0.12)' }}>
      <div style={{ fontWeight: 'bold', marginBottom: 4 }}>{p.fullTime}</div>
      <div>Giá trị thực: <b>{fmtNum(p.value)}</b></div>
      {isAnomaly && (
        <div style={{ marginTop: 4, borderTop: '1px dashed #eee', paddingTop: 4 }}>
          <div style={{ color: '#cf1322' }}>⚠ Điểm bất thường</div>
          <div style={{ color: '#d46b08' }}>Giá trị dự báo: <b>{fmtNum(p.predictedValue)}</b></div>
          <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 2 }}>
            Phát hiện: {p.anomalyMethod || '?'}
            {p.predictedMethod ? ` · Dự báo: ${p.predictedMethod}` : ''}
            {p.anomalyScore != null ? ` · score ${Number(p.anomalyScore).toFixed(2)}` : ''}
          </div>
        </div>
      )}
    </div>
  );
};

const WeatherDetail = () => {
  const { sensorId } = useParams();
  const [searchParams] = useSearchParams();
  const fieldId = searchParams.get('fieldId');
  const groupId = searchParams.get('groupId');
  const navigate = useNavigate();

  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [dateRange, setDateRange] = useState(null);

  const isSoilHumidity = sensorId === 'soilHumidity';
  const isGroupScope = !!groupId;

  useEffect(() => {
    if (!sensorId) return;
    if (!fieldId && !groupId) {
      message.error('Thiếu thông tin cánh đồng hoặc nhóm để tải biểu đồ!');
      setLoading(false);
      return;
    }

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

    const fetchSoilHumidityCombined = async () => {
      const responses = await Promise.all(
        SOIL_SERIES.map((s) =>
          api.get('/sensor-values/history', {
            params: { fieldId, sensorId: s.id },
          })
        )
      );

      const latest = Math.max(
        ...responses.flatMap((r) => (r.data?.[0] ? [new Date(r.data[0].time).getTime()] : [])),
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
      return Array.from(buckets.values()).sort((a, b) => a.t - b.t);
    };

    const fetchSingle = async () => {
      const url = isGroupScope ? '/sensor-values/group-history' : '/sensor-values/history';
      const params = isGroupScope ? { groupId, sensorId } : { fieldId, sensorId };
      const response = await api.get(url, { params });
      if (!response.data || response.data.length === 0) return [];

      const latest = new Date(response.data[0].time).getTime();
      const { startCutoff, endCutoff } = resolveRange(latest);

      const rows = response.data
        .filter((item) => {
          const t = new Date(item.time).getTime();
          return t >= startCutoff && t <= endCutoff;
        })
        .reverse()
        .map((item) => ({
          t: new Date(item.time).getTime(),
          time: formatTime(item.time),
          value: item.value,
          fullTime: formatFullTime(item.time),
        }));

      // Overlay anomaly corrections written by the BE (ml-service detection) —
      // group-scoped weather sensors only. Mark the anomalous reading and attach
      // the forecaster's predicted value at that time, matched to the nearest reading.
      if (isGroupScope && rows.length > 0) {
        try {
          const corrRes = await api.get('/sensor-values/corrections', {
            params: { groupId, sensorId },
          });
          const corrections = (corrRes.data || []).filter((c) => {
            const t = new Date(c.time).getTime();
            return t >= startCutoff && t <= endCutoff;
          });
          const TOLERANCE_MS = 2 * 60 * 60 * 1000; // nearest reading within 2h
          corrections.forEach((c) => {
            const tc = new Date(c.time).getTime();
            let best = null;
            let bestDiff = Infinity;
            for (const row of rows) {
              const d = Math.abs(row.t - tc);
              if (d < bestDiff) {
                bestDiff = d;
                best = row;
              }
            }
            if (best && bestDiff <= TOLERANCE_MS) {
              best.anomalyValue = c.actual != null ? c.actual : best.value;
              best.predictedValue = c.predicted;
              best.anomalyMethod = c.method;
              best.predictedMethod = c.predictedMethod;
              best.anomalyScore = c.score;
            }
          });
        } catch (err) {
          console.warn('Không tải được dữ liệu bất thường:', err?.message);
        }
      }

      return rows;
    };

    const run = async () => {
      setLoading(true);
      try {
        const result = isSoilHumidity ? await fetchSoilHumidityCombined() : await fetchSingle();
        setData(result);
      } catch (err) {
        console.error('Lỗi tải dữ liệu lịch sử:', err);
        message.error('Lỗi tải dữ liệu lịch sử');
      } finally {
        setLoading(false);
      }
    };

    run();
  }, [sensorId, fieldId, groupId, isSoilHumidity, isGroupScope, dateRange]);

  const sensorNameVi = SENSOR_NAMES_VI[sensorId] || sensorId;

  return (
    <div style={{ padding: '24px' }}>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>
          Quay lại trạm quan trắc
        </Button>
        <Space>
          <Text strong>Chọn thời gian:</Text>
          <RangePicker
            value={dateRange}
            onChange={(dates) => setDateRange(dates)}
            format="DD/MM/YYYY"
            placeholder={['Từ ngày', 'Đến ngày']}
          />
        </Space>
      </Space>

      <Card
        title={<Title level={4} style={{ margin: 0 }}>Biểu đồ trực quan: {sensorNameVi}</Title>}
        extra={<Text type="secondary">{dateRange ? 'Dữ liệu tùy chỉnh' : 'Dữ liệu 24h qua'} • {isGroupScope ? 'Nguồn: nhóm (chia sẻ)' : 'Nguồn: cánh đồng'}</Text>}
      >
        {loading ? (
          <div style={{ height: 400, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Spin indicator={<LoadingOutlined style={{ fontSize: 40 }} spin />} />
          </div>
        ) : data.length > 0 && isSoilHumidity ? (
          <div style={{ width: '100%', height: 450 }}>
            <ResponsiveContainer>
              <LineChart data={data} margin={{ top: 10, right: 30, left: 0, bottom: 20 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="time" interval="preserveStartEnd" minTickGap={40} />
                <YAxis unit="%" domain={[0, 100]} />
                <Tooltip labelFormatter={(_, payload) => payload[0]?.payload?.fullTime} />
                <Legend verticalAlign="top" wrapperStyle={{ paddingBottom: 15 }} />
                <Brush dataKey="time" height={30} stroke="#13c2c2" />
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
        ) : data.length > 0 ? (
          <div style={{ width: '100%', height: 450 }}>
            <ResponsiveContainer>
              <ComposedChart data={data} margin={{ top: 10, right: 30, left: 0, bottom: 20 }}>
                <defs>
                  <linearGradient id="colorValue" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={getChartColor(sensorId)} stopOpacity={0.8} />
                    <stop offset="95%" stopColor={getChartColor(sensorId)} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="time" interval="preserveStartEnd" minTickGap={40} />
                <YAxis />
                <Tooltip content={<ChartTooltip />} />
                <Legend verticalAlign="top" wrapperStyle={{ paddingBottom: 15 }} />
                <Area
                  type="monotone"
                  dataKey="value"
                  name="Giá trị thực"
                  stroke={getChartColor(sensorId)}
                  fillOpacity={1}
                  fill="url(#colorValue)"
                  dot={false}
                  isAnimationActive={false}
                />
                <Line
                  type="monotone"
                  dataKey="predictedValue"
                  name="Giá trị dự báo (SARIMA)"
                  stroke="transparent"
                  legendType="circle"
                  connectNulls={false}
                  isAnimationActive={false}
                  dot={{ r: 5, fill: '#faad14', stroke: '#ad6800', strokeWidth: 1 }}
                  activeDot={{ r: 6 }}
                />
                <Line
                  type="monotone"
                  dataKey="anomalyValue"
                  name="Điểm bất thường"
                  stroke="transparent"
                  legendType="circle"
                  connectNulls={false}
                  isAnimationActive={false}
                  dot={{ r: 5, fill: '#ff4d4f', stroke: '#a8071a', strokeWidth: 1 }}
                  activeDot={{ r: 7 }}
                />
                <Brush dataKey="time" height={30} stroke={getChartColor(sensorId)} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <Empty description="Không có dữ liệu lịch sử cho khoảng thời gian này" />
        )}
      </Card>
    </div>
  );
};

export default WeatherDetail;
