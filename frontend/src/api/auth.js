import apiClient from './client';

export const authAPI = {
  register: async (email, password) => {
    const response = await apiClient.post('/auth/register', { email, password });
    if (response.token) {
      localStorage.setItem('token', response.token);
      localStorage.setItem('userEmail', response.email);
    }
    return response;
  },

  login: async (email, password) => {
    const response = await apiClient.post('/auth/login', { email, password });
    if (response.token) {
      localStorage.setItem('token', response.token);
      localStorage.setItem('userEmail', response.email);
    }
    return response;
  },

  logout: () => {
    localStorage.removeItem('token');
    localStorage.removeItem('userEmail');
  },

  isAuthenticated: () => {
    return !!localStorage.getItem('token');
  },

  getCurrentUser: () => {
    return localStorage.getItem('userEmail');
  },
};