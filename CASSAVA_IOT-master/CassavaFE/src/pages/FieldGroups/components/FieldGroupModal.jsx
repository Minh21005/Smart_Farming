import React, { useEffect } from 'react';
import { Modal, Form, Input } from 'antd';

const FieldGroupModal = ({ open, onCancel, onSubmit, initialData }) => {
  const [form] = Form.useForm();

  useEffect(() => {
    if (open) {
      if (initialData) {
        form.setFieldsValue(initialData);
      } else {
        form.resetFields();
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
      title={initialData ? 'Chỉnh sửa nhóm cánh đồng' : 'Thêm nhóm cánh đồng mới'}
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      okText="Lưu lại"
      cancelText="Hủy"
      width="90%"
      style={{ maxWidth: 520 }}
    >
      <Form form={form} layout="vertical" name="field_group_form">
        <Form.Item
          name="name"
          label="Tên nhóm"
          rules={[{ required: true, message: 'Vui lòng nhập tên nhóm!' }]}
        >
          <Input placeholder="Ví dụ: Trạm thời tiết khu A" />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default FieldGroupModal;
