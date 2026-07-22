import React, { useState, useEffect } from 'react';
import { Typography, Table, Select, Space, Tag, message } from 'antd';
import dayjs from 'dayjs';
import fieldService from '../../../services/fieldService';

const { Title, Text } = Typography;

const formatSeasonLabel = (s) => {
  const start = s.cropStartTime ? dayjs(s.cropStartTime).format('DD/MM/YYYY') : '—';
  if (s.isCurrent) {
    const end = s.cropEndTime ? dayjs(s.cropEndTime).format('DD/MM/YYYY') : 'hiện tại';
    return `${start} → ${end}`;
  }
  const end = s.cropEndTime ? dayjs(s.cropEndTime).format('DD/MM/YYYY') : '—';
  return `${start} → ${end}`;
};

const HistoryTab = ({ fieldId = 'fieldTest' }) => {
  const [loading, setLoading] = useState(false);
  const [dataSource, setDataSource] = useState([]);
  const [seasons, setSeasons] = useState([]);
  const [selectedCrop, setSelectedCrop] = useState(null); // ISO string of cropStartTime

  useEffect(() => {
    let cancelled = false;
    const loadSeasons = async () => {
      try {
        const res = await fieldService.get(`/field/${fieldId}/seasons`);
        if (cancelled) return;
        const list = res.data || [];
        setSeasons(list);
        const current = list.find((s) => s.isCurrent) || list[0];
        setSelectedCrop(current?.cropStartTime || null);
      } catch (err) {
        console.error('Lỗi tải danh sách vụ:', err);
        message.error('Không thể tải danh sách vụ mùa!');
      }
    };
    loadSeasons();
    return () => { cancelled = true; };
  }, [fieldId]);

  useEffect(() => {
    if (!fieldId || selectedCrop === null) return;
    let cancelled = false;
    const fetchHistory = async () => {
      setLoading(true);
      try {
        const params = { fieldId };
        if (selectedCrop) params.cropStartTime = selectedCrop;
        const response = await fieldService.get('/irrigation-history', { params });
        if (cancelled) return;
        const data = response?.data || response;
        setDataSource(Array.isArray(data) ? data : []);
      } catch (error) {
        console.error('Fetch error:', error);
        if (!cancelled) message.error('Lỗi khi tải lịch sử tưới tiêu!');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    fetchHistory();
    return () => { cancelled = true; };
  }, [fieldId, selectedCrop]);

  const columns = [
    {
      title: 'Người tưới',
      dataIndex: 'userName',
      key: 'userName',
      render: (text) => <b>{text === 'admin' ? 'Hệ thống' : text}</b>,
    },
    {
      title: 'Thời gian bắt đầu',
      dataIndex: 'time',
      key: 'time',
      render: (time) => dayjs(time).format('DD/MM/YYYY HH:mm:ss'),
      sorter: (a, b) => dayjs(a.time).unix() - dayjs(b.time).unix(),
    },
    {
      title: 'Lượng nước (mm)',
      dataIndex: 'amount',
      key: 'amount',
      render: (val) => (val ? val.toFixed(2) : '0'),
    },
    {
      title: 'Thời gian tưới (Phút)',
      dataIndex: 'duration',
      key: 'duration',
      render: (val) => (val ? val.toFixed(1) : '0'),
    },
  ];

  const totalAmount = dataSource.reduce((acc, r) => acc + (Number(r.amount) || 0), 0);
  const totalDuration = dataSource.reduce((acc, r) => acc + (Number(r.duration) || 0), 0);

  return (
    <div style={{ padding: '16px 0' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
        <Title level={4} style={{ margin: 0 }}>Lịch sử tưới tiêu</Title>
        <Space size={8} wrap>
          <Text strong>Mùa vụ:</Text>
          <Select
            style={{ minWidth: 260 }}
            value={selectedCrop}
            onChange={setSelectedCrop}
            options={seasons.map((s) => ({
              value: s.cropStartTime,
              label: (
                <Space size={6}>
                  <span>{formatSeasonLabel(s)}</span>
                  {s.isCurrent && <Tag color="green" style={{ margin: 0 }}>Đang chạy</Tag>}
                </Space>
              ),
            }))}
            placeholder="Chọn mùa vụ"
            notFoundContent="Chưa có vụ nào"
          />
        </Space>
      </Space>
      {dataSource.length > 0 && (
        <Space size={12} wrap style={{ marginBottom: 12 }}>
          <Tag color="blue" style={{ padding: '4px 10px', fontSize: 13 }}>
            Tổng số lần tưới: <b>{dataSource.length}</b>
          </Tag>
          <Tag color="cyan" style={{ padding: '4px 10px', fontSize: 13 }}>
            Tổng lượng nước: <b>{totalAmount.toFixed(2)} mm</b>
          </Tag>
          <Tag color="geekblue" style={{ padding: '4px 10px', fontSize: 13 }}>
            Tổng thời gian: <b>{totalDuration.toFixed(1)} phút</b>
          </Tag>
        </Space>
      )}
      <Table
        columns={columns}
        dataSource={dataSource}
        rowKey="id"
        loading={loading}
        pagination={{
          pageSize: 5,
          placement: 'bottomCenter',
          showLessItems: true,
          showSizeChanger: false,
          hideOnSinglePage: true,
          style: {
            display: 'flex',
            justifyContent: 'center',
            float: 'none',
            width: '100%',
          },
        }}
        bordered
        locale={{ emptyText: 'Không có dữ liệu lịch sử cho vụ này' }}
      />
    </div>
  );
};

export default HistoryTab;
