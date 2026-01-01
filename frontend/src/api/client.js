const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

// Helper to get token from localStorage
const getToken = () => localStorage.getItem('token');

// Helper to handle response
const handleResponse = async (response) => {
  const data = await response.json().catch(() => null);
  
  if (!response.ok) {
    const error = data?.message || `HTTP error! status: ${response.status}`;
    throw new Error(error);
  }
  
  return data;
};

// API client with all methods
const apiClient = {
  // GET request
  get: async (endpoint) => {
    const token = getToken();
    const headers = {
      'Content-Type': 'application/json',
    };
    
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    
    const response = await fetch(`${API_BASE_URL}${endpoint}`, {
      method: 'GET',
      headers,
    });
    
    return handleResponse(response);
  },

  // POST request
  post: async (endpoint, data) => {
    const token = getToken();
    const headers = {
      'Content-Type': 'application/json',
    };
    
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    
    const response = await fetch(`${API_BASE_URL}${endpoint}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(data),
    });
    
    return handleResponse(response);
  },

  // PUT request
  put: async (endpoint, data) => {
    const token = getToken();
    const headers = {
      'Content-Type': 'application/json',
    };
    
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    
    const response = await fetch(`${API_BASE_URL}${endpoint}`, {
      method: 'PUT',
      headers,
      body: JSON.stringify(data),
    });
    
    return handleResponse(response);
  },

  // DELETE request
  delete: async (endpoint) => {
    const token = getToken();
    const headers = {
      'Content-Type': 'application/json',
    };
    
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    
    const response = await fetch(`${API_BASE_URL}${endpoint}`, {
      method: 'DELETE',
      headers,
    });
    
    // DELETE often returns 204 No Content
    if (response.status === 204) {
      return null;
    }
    
    return handleResponse(response);
  },
};

export default apiClient;