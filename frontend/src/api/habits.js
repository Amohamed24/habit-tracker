import apiClient from './client';

const HABITS_URL = import.meta.env.VITE_HABITS_URL || 'http://localhost:8080/api';
const isProduction = HABITS_URL.includes('railway');

export const habitsAPI = {
  // Get all habits for current user
  getAll: async () => {
    return apiClient.get('/habits');
  },

  // Get single habit by ID
  getById: async (id) => {
    return apiClient.get(`/habits/${id}`);
  },

  // Create new habit
  create: async (habitData) => {
    return apiClient.post('/habits', habitData);
  },

  // Update habit
  update: async (id, habitData) => {
    return apiClient.put(`/habits/${id}`, habitData);
  },

  // Delete habit
  delete: async (id) => {
    return apiClient.delete(`/habits/${id}`);
  },

  // Mark habit as complete for today
  markComplete: async (id) => {
    if (isProduction) {
      return apiClient.post(`/habits/${id}/complete`);
    }
    return apiClient.post(`/habits/${id}/toggle`);
  },

  // Unmark habit completion for today
  unmarkComplete: async (id) => {
    if (isProduction) {
      return apiClient.delete(`/habits/${id}/complete`);
    }
    return apiClient.post(`/habits/${id}/toggle`);
  },
};