import React, { useState, useEffect, useCallback } from 'react';
import {
  Form, InputNumber, Card, Row, Col, Divider, Button, Space, Tag, Table,
  DatePicker, message, Modal, Popconfirm, Empty, Alert, Tooltip
} from 'antd';
import {
  PlayCircleOutlined, ClockCircleOutlined, ExclamationCircleOutlined,
  ReloadOutlined, ThunderboltOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import fieldService from '../../../services/fieldService';

const { confirm } = Modal;

const STATUS_META = {
  PENDING:   { color: 'blue',     label: 'Chờ gửi' },
  SENT:      { color: 'cyan',     label: 'Đã gửi edge' },
  RUNNING:   { color: 'gold',     label: 'Đang tưới' },
  DONE:      { color: 'green',    label: 'Hoàn tất' },
  CANCELLED: { color: 'default',  label: 'Đã hủy' },
  FAILED:    { color: 'red',      label: 'Thất bại' },
  NO_ACK:    { color: 'volcano',  label: 'Không phản hồi' },
};

const ManualIrrigationTab = ({ fieldId, fieldName, fieldMode = 'OPERATION' }) => {
  const isSimulation = fieldMode === 'SIMULATION';
  const [scheduledAt, setScheduledAt] = useState(dayjs().add(5, 'minute'));
  const [durationMinutes, setDurationMinutes] = useState(10);
  const [submitting, setSubmitting] = useState(false);
  const [schedules, setSchedules] = useState([]);
  const [loading, setLoading] = useState(false);

  const userName = (() => {
    try {
      const u = JSON.parse(localStorage.getItem('user') || '{}');
      return u.username || u.userName || u.email || 'manual';
    } catch {
      return 'manual';
    }
  })();

  const loadSchedules = useCallback(async () => {
    if (!fieldId) return;
    setLoading(true);
    try {
      const res = await fieldService.get('/irrigation-schedule', { params: { fieldId } });
      setSchedules(res.data || []);
    } catch (err) {
      console.error(err);
      message.error('Không tải được danh sách lịch tưới');
    } finally {
      setLoading(false);
    }
  }, [fieldId]);

  useEffect(() => {
    loadSchedules();
  }, [loadSchedules]);

  const submitSchedule = async (whenDayjs) => {
    if (!whenDayjs) {
      return message.error('Vui lòng chọn thời điểm tưới!');
    }
    if (!durationMinutes || durationMinutes <= 0) {
      return message.error('Vui lòng nhập thời gian tưới (phút)!');
    }
    setSubmitting(true);
    try {
      await fieldService.post('/irrigation-schedule', {
        fieldId,
        scheduledTime: whenDayjs.toDate().toISOString(),
        durationSeconds: durationMinutes * 60,
        userName,
      });
      message.success('Đã tạo lịch tưới');
      loadSchedules();
    } catch (err) {
      const msg = err?.response?.data?.message || err?.response?.data || err.message;
      message.error('Tạo lịch thất bại: ' + msg);
    } finally {
      setSubmitting(false);
    }
  };

  const showConfirmSchedule = () => {
    confirm({
      title: 'Xác nhận đặt lịch tưới?',
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>Cánh đồng: <b>{fieldName || fieldId}</b></p>
          <p>Thời điểm: <b>{scheduledAt?.format('DD/MM/YYYY HH:mm')}</b></p>
          <p>Thời gian tưới: <b>{durationMinutes} phút</b></p>
        </div>
      ),
      okText: 'Đặt lịch',
      cancelText: 'Hủy',
      onOk: () => submitSchedule(scheduledAt),
    });
  };

  const showConfirmInstant = () => {
    confirm({
      title: 'Tưới ngay?',
      icon: <ExclamationCircleOutlined />,
      content: (
        <div>
          <p>Cánh đồng: <b>{fieldName || fieldId}</b></p>
          <p>Thời gian tưới: <b>{durationMinutes} phút</b></p>
          <p style={{ color: '#faad14' }}>
            <i>Lệnh sẽ được edge computer thực thi ngay khi phát hiện (thường &lt; 1 phút).</i>
          </p>
        </div>
      ),
      okText: 'Kích hoạt',
      cancelText: 'Hủy',
      onOk: () => submitSchedule(dayjs()),
    });
  };

  const cancelSchedule = async (id) => {
    try {
      await fieldService.put(`/irrigation-schedule/${id}/cancel`);
      message.success('Đã hủy lịch');
      loadSchedules();
    } catch (err) {
      const msg = err?.response?.data?.message || err?.response?.data || err.message;
      message.error('Hủy lịch thất bại: ' + msg);
    }
  };

  const stopSchedule = async (id) => {
    try {
      await fieldService.put(`/irrigation-schedule/${id}/stop`);
      message.success('Đã gửi lệnh dừng tưới');
      loadSchedules();
    } catch (err) {
      const msg = err?.response?.data?.message || err?.response?.data || err.message;
      message.error('Dừng tưới thất bại: ' + msg);
    }
  };

  const columns = [
    {
      title: 'Thời điểm',
      dataIndex: 'scheduledTime',
      render: (v) => v ? dayjs(v).format('DD/MM/YYYY HH:mm') : '—',
      sorter: (a, b) => new Date(a.scheduledTime) - new Date(b.scheduledTime),
      defaultSortOrder: 'descend',
    },
    {
      title: 'Thời lượng',
      dataIndex: 'durationSeconds',
      render: (v) => v ? `${(v / 60).toFixed(1)} phút` : '—',
      width: 110,
    },
    {
      title: 'Lượng (mm)',
      dataIndex: 'amount',
      render: (v) => (v != null ? Number(v).toFixed(2) : '—'),
      width: 130,
    },
    {
      title: 'Trạng thái',
      dataIndex: 'status',
      render: (s, row) => {
        const meta = STATUS_META[s] || { color: 'default', label: s || '—' };
        const tip = [];
        if (row.sentAt)     tip.push(`Gửi edge: ${dayjs(row.sentAt).format('DD/MM HH:mm:ss')}`);
        if (row.startedAt)  tip.push(`Bắt đầu: ${dayjs(row.startedAt).format('DD/MM HH:mm:ss')}`);
        if (row.finishedAt) tip.push(`Kết thúc: ${dayjs(row.finishedAt).format('DD/MM HH:mm:ss')}`);
        if (row.errorMessage) tip.push(`Lỗi: ${row.errorMessage}`);
        const tag = <Tag color={meta.color}>{meta.label}</Tag>;
        return tip.length ? <Tooltip title={tip.join(' • ')}>{tag}</Tooltip> : tag;
      },
      width: 140,
    },
    {
      title: 'Người tạo',
      dataIndex: 'userName',
      width: 140,
    },
    {
      title: '',
      key: 'actions',
      width: 110,
      render: (_, row) => {
        if (row.status === 'PENDING') return (
          <Popconfirm
            title="Hủy lịch này?"
            okText="Hủy lịch"
            cancelText="Không"
            onConfirm={() => cancelSchedule(row.id)}
          >
            <Button danger size="small">Hủy</Button>
          </Popconfirm>
        );
        if (row.status === 'RUNNING') return (
          <Popconfirm
            title="Dừng tưới ruộng này?"
            okText="Dừng"
            cancelText="Không"
            onConfirm={() => stopSchedule(row.id)}
          >
            <Button danger size="small">Dừng</Button>
          </Popconfirm>
        );
        return null;
      },
    },
  ];

  return (
    <div style={{ padding: '20px 0' }}>
      <Row gutter={[24, 24]}>
        <Col xs={24} md={10}>
          <Card title={<span><ClockCircleOutlined /> Đặt lịch tưới</span>}>
            {isSimulation && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
                message="Cánh đồng đang ở chế độ Mô phỏng"
                description="Lệnh tưới sẽ được lưu và mô phỏng vòng đời (chờ → đang tưới → hoàn tất) nhưng không gửi xuống edge. Đổi sang chế độ Thực thi nếu muốn điều khiển van bơm thật."
              />
            )}
            <Form layout="vertical">
              <Form.Item label="Thời điểm tưới">
                <DatePicker
                  showTime={{ format: 'HH:mm' }}
                  format="DD/MM/YYYY HH:mm"
                  value={scheduledAt}
                  onChange={setScheduledAt}
                  style={{ width: '100%' }}
                  disabledDate={(d) => d && d.isBefore(dayjs().startOf('day'))}
                />
              </Form.Item>
              <Form.Item label="Thời gian tưới (phút)">
                <InputNumber
                  min={0.1}
                  step={0.5}
                  value={durationMinutes}
                  onChange={setDurationMinutes}
                  style={{ width: '100%' }}
                />
              </Form.Item>

              <Divider />

              <Space direction="vertical" style={{ width: '100%' }}>
                <Button
                  type="primary"
                  icon={<PlayCircleOutlined />}
                  size="large"
                  onClick={showConfirmSchedule}
                  loading={submitting}
                  block
                >
                  Đặt lịch
                </Button>
                <Button
                  icon={<ThunderboltOutlined />}
                  size="large"
                  onClick={showConfirmInstant}
                  loading={submitting}
                  block
                >
                  Tưới ngay
                </Button>
              </Space>
            </Form>
          </Card>
        </Col>

        <Col xs={24} md={14}>
          <Card
            title="Danh sách lịch tưới"
            extra={
              <Button
                size="small"
                icon={<ReloadOutlined />}
                onClick={loadSchedules}
                loading={loading}
              >
                Làm mới
              </Button>
            }
          >
            <Table
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={schedules}
              loading={loading}
              locale={{ emptyText: <Empty description="Chưa có lịch tưới nào" /> }}
              pagination={{ pageSize: 8 }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ManualIrrigationTab;
