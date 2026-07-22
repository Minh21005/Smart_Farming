// src/pages/Fields/FieldDetail/index.jsx
import React, { useState, useEffect } from 'react';
import { Tabs, Card, Typography, Breadcrumb, Button, Tag } from 'antd';
import { ArrowLeftOutlined, ExperimentOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';

import IrrigationTab from './IrrigationTab';
import YieldTab from './YieldTab';
import HistoryTab from './HistoryTab';
import DiseaseTab from './DiseaseTab';
import SimulationDashboard from '../components/SimulationDashboard';
import fieldService from '../../../services/fieldService';
import ManualIrrigationTab from './ManualIrrigationTab';
const { Title } = Typography;

const FieldDetailIndex = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const [fieldName, setFieldName] = useState('');
  const [isAuto, setIsAuto] = useState(false);
  const [fieldMode, setFieldMode] = useState('SIMULATION');

  useEffect(() => {
    fieldService.get(`/field/${id}`)
      .then(res => {
        setFieldName(res.data?.name || id);
        setIsAuto(res.data?.autoIrrigation);
        setFieldMode(res.data?.mode || 'SIMULATION');
      })
      .catch(() => setFieldName(id));
  }, [id]);

  const tabItems = [
    { key: '1', label: 'Theo dõi tưới tiêu', children: <IrrigationTab fieldId={id} /> },
   ...(isAuto === true ? [{
      key: '2',
      label: 'Dự đoán sản lượng',
      children: <SimulationDashboard fieldId={id} fieldName={fieldName} />
    }] : []),
    ...(isAuto === false ? [{
      key: '2-manual',
      label: 'Cài đặt tưới tay',
      children: <ManualIrrigationTab fieldId={id} fieldName={fieldName} fieldMode={fieldMode} />
    }] : []),
    { key: '3', label: 'Lịch sử tưới', children: <HistoryTab fieldId={id} />},
    { key: '4', label: 'Tình trạng bệnh', children: <DiseaseTab /> },
  ];

  const modeTag = fieldMode === 'OPERATION'
    ? <Tag color="volcano" icon={<ThunderboltOutlined />} style={{ marginLeft: 12 }}>Thực thi</Tag>
    : <Tag color="purple"  icon={<ExperimentOutlined />}  style={{ marginLeft: 12 }}>Mô phỏng</Tag>;

  return (
    <div>
      <Breadcrumb style={{ marginBottom: '16px' }}>
        <Breadcrumb.Item>
          <a onClick={() => navigate(-1)}>Danh sách cánh đồng</a>
        </Breadcrumb.Item>
        <Breadcrumb.Item>{fieldName}</Breadcrumb.Item>
      </Breadcrumb>

      <Card>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '20px' }}>
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate(-1)}
            style={{ marginRight: '16px' }}
          />
          <Title level={3} style={{ margin: 0 }}>{fieldName}</Title>
          {modeTag}
        </div>

        <Tabs defaultActiveKey="1" items={tabItems} />
      </Card>
    </div>
  );
};

export default FieldDetailIndex;
