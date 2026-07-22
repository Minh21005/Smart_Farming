// src/pages/Fields/FieldDetail/DiseaseTab.jsx
import React from 'react';
import { Typography, Card, Tag, List } from 'antd';

const { Title } = Typography;

const DiseaseTab = () => {
  const diseaseData = [
    {
      name: 'Bệnh khảm lá (Mosaic)',
      status: 'Nguy cơ thấp',
      color: 'green',
      description: 'Không phát hiện dấu hiệu khảm lá trên diện rộng.',
    },
    {
      name: 'Bệnh xoăn lá',
      status: 'Cảnh báo',
      color: 'orange',
      description: 'Phát hiện một số khu vực có hiện tượng xoăn lá, cần kiểm tra rệp sáp.',
    },
  ];

  return (
    <div style={{ padding: '16px 0' }}>
      <Title level={4}>Chẩn đoán & Tình trạng bệnh</Title>
      <Card bordered={false} style={{ background: '#fafafa' }}>
        <List
          itemLayout="horizontal"
          dataSource={diseaseData}
          renderItem={(item) => (
            <List.Item>
              <List.Item.Meta
                title={
                  <span>
                    {item.name} <Tag color={item.color} style={{ marginLeft: 8 }}>{item.status}</Tag>
                  </span>
                }
                description={item.description}
              />
            </List.Item>
          )}
        />
      </Card>
    </div>
  );
};

export default DiseaseTab;