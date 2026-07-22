// src/services/groupService.js
// Axios instance for /mongo/field-group endpoints (Field Group + Field Group Sensor)
import axios from 'axios';

const groupService = axios.create({
  baseURL: `${import.meta.env.VITE_API_BASE || 'http://localhost:8081'}/mongo/field-group`,
  headers: {
    'Content-Type': 'application/json',
  }
});

groupService.interceptors.request.use((config) => {
  const user = JSON.parse(localStorage.getItem('user'));
  if (user?.accessToken) {
    config.headers['Authorization'] = `Bearer ${user.accessToken}`;
  }
  return config;
}, (error) => Promise.reject(error));

export default groupService;
