/** 
// src/pages/Fields/components/FieldModal.jsx
import React, { useEffect } from 'react';
import { Modal, Form, Input, InputNumber } from 'antd';

const FieldModal = ({ open, onCancel, onSubmit, initialData }) => {
  const [form] = Form.useForm();

  // Tự động điền dữ liệu vào form nếu là chế độ "Sửa"
  useEffect(() => {
    if (open) {
      if (initialData) {
        form.setFieldsValue(initialData);
      } else {
        form.resetFields(); // Làm sạch form nếu là "Thêm mới"
      }
    }
  }, [open, initialData, form]);

  const handleOk = () => {
    form.validateFields().then((values) => {
      onSubmit(values);
      form.resetFields();
    });
  };

  return (
    <Modal
      title={initialData ? "Chỉnh sửa tham số cánh đồng" : "Thêm cánh đồng mới"}
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      okText="Lưu lại"
      cancelText="Hủy"
      width={600}
    >
      <Form form={form} layout="vertical" name="field_form">
        <Form.Item
          name="name"
          label="Tên cánh đồng"
          rules={[{ required: true, message: 'Vui lòng nhập tên cánh đồng!' }]}
        >
          <Input placeholder="Ví dụ: Cánh đồng khu A" />
        </Form.Item>

        <Form.Item
          name="area"
          label="Diện tích (ha)"
          rules={[{ required: true, message: 'Vui lòng nhập diện tích!' }]}
        >
          <InputNumber style={{ width: '100%' }} min={0} step={0.1} />
        </Form.Item>

        {/* Các tham số theo yêu cầu của hệ thống IoT */
        /** 
        <div style={{ display: 'flex', gap: '16px' }}>
          <Form.Item name="totalHoles" label="Tổng số lỗ tưới" style={{ flex: 1 }}>
            <InputNumber style={{ width: '100%' }} min={0} />
          </Form.Item>

          <Form.Item name="dripRate" label="Tốc độ nhỏ giọt (L/h)" style={{ flex: 1 }}>
            <InputNumber style={{ width: '100%' }} min={0} step={0.5} />
          </Form.Item>
        </div>

        <div style={{ display: 'flex', gap: '16px' }}>
          <Form.Item name="holeSpacing" label="Khoảng cách lỗ (cm)" style={{ flex: 1 }}>
            <InputNumber style={{ width: '100%' }} min={0} />
          </Form.Item>

          <Form.Item name="targetHumidity" label="Độ ẩm duy trì (%)" style={{ flex: 1 }}>
            <InputNumber style={{ width: '100%' }} min={0} max={100} />
          </Form.Item>
        </div>
      </Form>
    </Modal>
  );
};

export default FieldModal;
*/
// src/pages/Fields/components/FieldModal.jsx
import React, { useEffect, useState } from 'react';
import { Modal, Form, Input, InputNumber, Switch, Row, Col, Divider, Select, DatePicker, Radio, message } from 'antd';
import { ExperimentOutlined, ThunderboltOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import groupService from '../../../services/groupService';

const FieldModal = ({ open, onCancel, onSubmit, initialData }) => {
  const [form] = Form.useForm();
  const [groups, setGroups] = useState([]);
  const [groupsLoading, setGroupsLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const loadGroups = async () => {
      setGroupsLoading(true);
      try {
        const res = await groupService.get('');
        if (!cancelled) setGroups(res.data || []);
      } catch (err) {
        console.error('Lỗi tải danh sách nhóm:', err);
        if (!cancelled) message.error('Không thể tải danh sách nhóm cánh đồng!');
      } finally {
        if (!cancelled) setGroupsLoading(false);
      }
    };
    loadGroups();
    return () => { cancelled = true; };
  }, [open]);

  useEffect(() => {
    if (open) {
      if (initialData) {
        form.setFieldsValue({
          ...initialData,
          mode: initialData.mode || 'SIMULATION',
          startTime: initialData.startTime ? dayjs(initialData.startTime) : null,
          endTime: initialData.endTime ? dayjs(initialData.endTime) : null,
        });
      } else {
        form.resetFields();
        form.setFieldsValue({ startTime: dayjs(), endTime: null, mode: 'SIMULATION' });
      }
    }
  }, [open, initialData, form]);

  const handleOk = () => {
    form.validateFields().then((values) => {
      const payload = {
        ...values,
        startTime: values.startTime ? values.startTime.toDate().toISOString() : null,
        endTime: values.endTime ? values.endTime.toDate().toISOString() : null,
      };
      onSubmit(payload);
      form.resetFields();
    });
  };

  return (
    <Modal
      title={initialData ? "Chỉnh sửa tham số cánh đồng" : "Thêm cánh đồng mới"}
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      okText="Lưu lại"
      cancelText="Hủy"
      width="90%"
      style={{ maxWidth: 700 }}
    >
      <Form form={form} layout="vertical" name="field_form">
        <Form.Item
          name="mode"
          label="Loại cánh đồng"
          rules={[{ required: true, message: 'Vui lòng chọn loại cánh đồng!' }]}
          extra="Mô phỏng: chỉ chạy mô hình, KHÔNG gửi lệnh tưới ra thiết bị. Thực thi: sẽ điều khiển van bơm thật khi MQTT bridge sẵn sàng."
          initialValue="SIMULATION"
        >
          <Radio.Group buttonStyle="solid" style={{ width: '100%' }}>
            <Radio.Button value="SIMULATION" style={{ width: '50%', textAlign: 'center' }}>
              <ExperimentOutlined /> Mô phỏng
            </Radio.Button>
            <Radio.Button value="OPERATION" style={{ width: '50%', textAlign: 'center' }}>
              <ThunderboltOutlined /> Thực thi
            </Radio.Button>
          </Radio.Group>
        </Form.Item>

        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="name"
              label="Tên cánh đồng"
              rules={[{ required: true, message: 'Vui lòng nhập tên cánh đồng!' }]}
            >
              <Input placeholder="Ví dụ: Cánh đồng A1" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12}>
            <Form.Item
              name="acreage"
              label="Diện tích (m²)"
              rules={[{ required: true, message: 'Vui lòng nhập diện tích!' }]}
            >
              <InputNumber style={{ width: '100%' }} min={0} step={0.1} />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item
          name="groupId"
          label="Nhóm cánh đồng (chia sẻ trạm thời tiết)"
          rules={[{ required: true, message: 'Vui lòng chọn nhóm cánh đồng!' }]}
          extra="5 cảm biến thời tiết (nhiệt độ, độ ẩm không khí, mưa, bức xạ, gió) dùng chung cho mọi cánh đồng trong nhóm."
        >
          <Select
            placeholder="Chọn nhóm cánh đồng"
            loading={groupsLoading}
            options={groups.map((g) => ({ value: g.id, label: g.name }))}
            showSearch
            optionFilterProp="label"
          />
        </Form.Item>

        <Row gutter={16}>
          <Col xs={24} sm={12}>
            <Form.Item
              name="startTime"
              label="Ngày bắt đầu vụ"
              rules={[{ required: true, message: 'Vui lòng chọn ngày bắt đầu vụ!' }]}
              extra="Có thể chọn ngày trong quá khứ."
            >
              <DatePicker
                style={{ width: '100%' }}
                format="DD/MM/YYYY"
                placeholder="Chọn ngày bắt đầu"
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={12}>
            <Form.Item
              name="endTime"
              label="Ngày kết thúc vụ"
              extra="Để trống nếu vụ đang chạy (mô phỏng tới hôm nay)."
              dependencies={['startTime']}
              rules={[
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    const start = getFieldValue('startTime');
                    if (!value || !start) return Promise.resolve();
                    return value.isAfter(start)
                      ? Promise.resolve()
                      : Promise.reject(new Error('Ngày kết thúc phải sau ngày bắt đầu'));
                  },
                }),
              ]}
            >
              <DatePicker
                style={{ width: '100%' }}
                format="DD/MM/YYYY"
                placeholder="Trống = vụ đang chạy"
                allowClear
              />
            </Form.Item>
          </Col>
        </Row>

        <Divider orientation="left" plain style={{ fontSize: '12px', color: '#999' }}>Thông số canh tác</Divider>

        <Row gutter={16}>
          <Col xs={24} sm={8}>
            <Form.Item name="fieldCapacity" label="Field Capacity (%)">
              <InputNumber style={{ width: '100%' }} min={0} max={100} step={1} placeholder="60" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="distanceBetweenRow" label="Khoảng cách hàng (m)">
              <InputNumber style={{ width: '100%' }} min={0} step={0.1} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="distanceBetweenHole" label="Khoảng cách lỗ (m)">
              <InputNumber style={{ width: '100%' }} min={0} step={0.1} />
            </Form.Item>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col xs={24} sm={8}>
            <Form.Item name="numberOfHoles" label="Tổng số lỗ tưới">
              <InputNumber style={{ width: '100%' }} min={0} precision={0} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="dripRate" label="Tốc độ nhỏ giọt (L/h)">
              <InputNumber style={{ width: '100%' }} min={0} step={0.5} />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="fertilizationLevel" label="Mức độ phân bón">
              <InputNumber style={{ width: '100%' }} min={0} step={0.1} />
            </Form.Item>
          </Col>
        </Row>

        <Divider />

        <Form.Item
          name="autoIrrigation"
          label="Tự động tưới tiêu"
          valuePropName="checked"
          initialValue={false}
        >
          <Switch checkedChildren="Bật" unCheckedChildren="Tắt" />
        </Form.Item>

        <Form.Item
          name="valveId"
          label="Van bơm gán cho cánh đồng (1-4)"
          rules={[{ required: true, message: 'Vui lòng chọn van bơm!' }]}
          extra="Van vật lý điều khiển cánh đồng này; dùng cho cả chế độ tự động và lịch tưới thủ công."
        >
          <Select
            placeholder="Chọn van"
            options={[1, 2, 3, 4].map((v) => ({ value: v, label: `Van ${v} (Pump${v})` }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default FieldModal;