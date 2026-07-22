// src/services/api.js
/*import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8000/api/v1', // Khớp với cấu trúc BE của cậu
});

api.interceptors.request.use((config) => {
  const user = JSON.parse(localStorage.getItem('user'));
  if (user?.accessToken) {
    config.headers['token'] = `Bearer ${user.accessToken}`;
  }
  return config;
});
export default api;
*/
// src/services/api.js
import axios from 'axios';

const authService = axios.create({
  baseURL: `${import.meta.env.VITE_API_BASE || 'http://localhost:8081'}/api`,
  headers: {
    'Content-Type': 'application/json',
  }
});

authService.interceptors.request.use((config) => {
  const user = JSON.parse(localStorage.getItem('user'));
  
  // Kiểm tra nếu có token thì đính kèm vào header Authorization
  if (user && user.accessToken) {
    config.headers['Authorization'] = `Bearer ${user.accessToken}`;
  }
  
  return config;
}, (error) => {
  return Promise.reject(error);
});

export default authService;