const AUTH_URL = import.meta.env.VITE_AUTH_URL || 'http://localhost:8081/api';
const HABITS_URL = import.meta.env.VITE_HABITS_URL || 'http://localhost:8080/api';

// Helper to get token from localStorage
const getToken = () => localStorage.getItem('token');

// Helper to handle response
const handleResponse = async (response) => {
  const data = await response.json().catch(() => null);
  
  if (!response.ok) {
    const error = data?.error || data?.message || `HTTP error! status: ${response.status}`;
    throw new Error(error);
  }
  
  return data;
};

// Auth API
export const authApi = {
  register: async (data) => {
    const response = await fetch(`${AUTH_URL}/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    return handleResponse(response);
  },
  
  login: async (data) => {
    const response = await fetch(`${AUTH_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    return handleResponse(response);
  },
};

// Habits API
export const habitsApi = {
  getAll: async () => {
    const response = await fetch(`${HABITS_URL}/habits`, {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
    });
    return handleResponse(response);
  },
  
  getById: async (id) => {
    const response = await fetch(`${HABITS_URL}/habits/${id}`, {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
    });
    return handleResponse(response);
  },
  
  create: async (data) => {
    const response = await fetch(`${HABITS_URL}/habits`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
      body: JSON.stringify(data),
    });
    return handleResponse(response);
  },
  
  update: async (id, data) => {
    const response = await fetch(`${HABITS_URL}/habits/${id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
      body: JSON.stringify(data),
    });
    return handleResponse(response);
  },
  
  delete: async (id) => {
    const response = await fetch(`${HABITS_URL}/habits/${id}`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
    });
    if (response.status === 204) return null;
    return handleResponse(response);
  },

  toggleComplete: async (id, isCurrentlyCompleted) => {
    const isProduction = HABITS_URL.includes('railway');
    
    if (isProduction) {
      // Monolith uses separate endpoints
      const method = isCurrentlyCompleted ? 'DELETE' : 'POST';
      const response = await fetch(`${HABITS_URL}/habits/${id}/complete`, {
        method: method,
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${getToken()}`,
        },
      });
      return handleResponse(response);
    } else {
      // Microservices use toggle
      const response = await fetch(`${HABITS_URL}/habits/${id}/toggle`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${getToken()}`,
        },
      });
      return handleResponse(response);
    }
  },
};

// Default export for backward compatibility
const apiClient = {
  get: async (endpoint) => {
    const url = endpoint.startsWith('/auth') ? AUTH_URL : HABITS_URL;
    const response = await fetch(`${url}${endpoint}`, {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
    });
    return handleResponse(response);
  },
  post: async (endpoint, data) => {
    const url = endpoint.startsWith('/auth') ? AUTH_URL : HABITS_URL;
    const response = await fetch(`${url}${endpoint}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
      body: JSON.stringify(data),
    });
    return handleResponse(response);
  },
  put: async (endpoint, data) => {
    const url = endpoint.startsWith('/auth') ? AUTH_URL : HABITS_URL;
    const response = await fetch(`${url}${endpoint}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
      body: JSON.stringify(data),
    });
    return handleResponse(response);
  },
  delete: async (endpoint) => {
    const url = endpoint.startsWith('/auth') ? AUTH_URL : HABITS_URL;
    const response = await fetch(`${url}${endpoint}`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${getToken()}`,
      },
    });
    if (response.status === 204) return null;
    return handleResponse(response);
  },
};

export default apiClient;