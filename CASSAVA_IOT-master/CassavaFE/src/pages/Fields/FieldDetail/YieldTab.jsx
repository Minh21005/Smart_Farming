// src/pages/Fields/FieldDetail/YieldTab.jsx
/** 
import React from 'react';
import { Typography, Card, Row, Col } from 'antd';

const { Title } = Typography;

const YieldTab = () => {
  return (
    <div style={{ padding: '16px 0' }}>
      <Title level={4}>Dự đoán sản lượng & Sinh trưởng</Title>
      <Row gutter={[16, 16]}>
        <Col span={12}>
          <Card title="Biểu đồ sản lượng cây sắn" bordered={false} style={{ background: '#fafafa' }}>
            <p style={{ height: '200px', textAlign: 'center', lineHeight: '200px', color: '#999' }}>
              (Khu vực hiển thị biểu đồ sản lượng)
            </p>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="Biểu đồ diện tích lá" bordered={false} style={{ background: '#fafafa' }}>
            <p style={{ height: '200px', textAlign: 'center', lineHeight: '200px', color: '#999' }}>
              (Khu vực hiển thị biểu đồ diện tích lá)
            </p>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default YieldTab;
*/
/** 
import React from 'react';
import { Row, Col, Card, Empty } from 'antd';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';

const YieldTab = ({ data }) => {
    // Nếu không có dữ liệu, hiện Empty để người dùng biết
    if (!data || data.length === 0) {
        return <Empty description="Chưa có dữ liệu mô phỏng" style={{ padding: 50 }} />;
    }

    const renderLineChart = (title, dataKey, color, unit) => (
        <Card title={title} bordered={false} style={{ marginBottom: 16, boxShadow: '0 2px 8px rgba(0,0,0,0.05)' }}>
            <div style={{ width: '100%', height: 280 }}>
                <ResponsiveContainer>
                    <LineChart data={data} syncId="simulationSync">
                        <CartesianGrid strokeDasharray="3 3" vertical={false} />
                        <XAxis dataKey="time" minTickGap={40} />
                        <YAxis />
                        <Tooltip formatter={(value) => [`${value} ${unit}`, title]} />
                        <Legend />
                        <Line 
                            type="monotone" 
                            dataKey={dataKey} 
                            stroke={color} 
                            dot={false} 
                            strokeWidth={2}
                            name={title}
                            animationDuration={1000}
                        />
                    </LineChart>
                </ResponsiveContainer>
            </div>
        </Card>
    );

    return (
        <Row gutter={[16, 16]}>
            <Col xs={24} lg={12}>{renderLineChart("Sản lượng (Yield)", "yield", "#8884d8", "kg")}</Col>
            <Col xs={24} lg={12}>{renderLineChart("Diện tích lá (Leaf Area)", "leafArea", "#82ca9d", "m2")}</Col>
            <Col xs={24} lg={12}>{renderLineChart("Lượng tưới (Irrigation)", "irrigation", "#0088FE", "mm")}</Col>
            <Col xs={24} lg={12}>{renderLineChart("Carbon linh động (Labile Carbon)", "labileCarbon", "#FFBB28", "g")}</Col>
        </Row>
    );
};

export default YieldTab;
*/
import React from 'react';
import { Row, Col, Card, Empty } from 'antd';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';

const YieldTab = ({ data }) => {
    if (!data || data.length === 0) {
        return <Empty description="Chưa có dữ liệu mô phỏng" style={{ padding: 50 }} />;
    }

    const renderAreaChart = (title, dataKey, color, unit) => (
        <Card title={title} bordered={false} style={{ marginBottom: 16, boxShadow: '0 2px 8px rgba(0,0,0,0.05)' }}>
            <div style={{ width: '100%', height: 280 }}>
                <ResponsiveContainer>
                    <AreaChart data={data} syncId="simulationSync">
                        {/* Định nghĩa dải màu Gradient để đồ thị trông chuyên nghiệp hơn */}
                        <defs>
                            <linearGradient id={`color${dataKey}`} x1="0" y1="0" x2="0" y2="1">
                                <stop offset="5%" stopColor={color} stopOpacity={0.8}/>
                                <stop offset="95%" stopColor={color} stopOpacity={0}/>
                            </linearGradient>
                        </defs>
                        
                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f0f0f0" />
                        <XAxis 
                            dataKey="time" 
                            minTickGap={40} 
                            tick={{fontSize: 12, fill: '#666'}}
                            axisLine={{stroke: '#ddd'}}
                        />
                        <YAxis 
                            tick={{fontSize: 12, fill: '#666'}}
                            axisLine={{stroke: '#ddd'}}
                        />
                        <Tooltip 
                            contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 12px rgba(0,0,0,0.1)' }}
                            formatter={(value) => [`${value} ${unit}`, title]} 
                        />
                        <Legend verticalAlign="top" height={36}/>
                        
                        <Area
                            type="monotone"
                            dataKey={dataKey}
                            stroke={color}
                            strokeWidth={3}
                            fillOpacity={1}
                            fill={`url(#color${dataKey})`} // Sử dụng Gradient đã định nghĩa ở trên
                            name={title}
                            animationDuration={1200}
                        />
                    </AreaChart>
                </ResponsiveContainer>
            </div>
        </Card>
    );

    return (
        <Row gutter={[16, 16]}>
            <Col xs={24} lg={12}>{renderAreaChart("Lượng tưới (Irrigation)", "irrigation", "#0088FE", "mm")}</Col>
            <Col xs={24} lg={12}>{renderAreaChart("Sản lượng (Yield)", "yield", "#8884d8", "kg")}</Col>
            <Col xs={24} lg={12}>{renderAreaChart("Diện tích lá (Leaf Area)", "leafArea", "#82ca9d", "m2")}</Col>
            <Col xs={24} lg={12}>{renderAreaChart("Carbon linh động (Labile Carbon)", "labileCarbon", "#FFBB28", "g")}</Col>
        </Row>
    );
};

export default YieldTab;