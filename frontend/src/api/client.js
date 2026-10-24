import axios from 'axios';

export const TOKEN_KEY = 'tradex.token';
export const UNAUTHORIZED_EVENT = 'tradex:unauthorized';

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const WS_BASE_URL =
  import.meta.env.VITE_WS_BASE_URL || API_BASE_URL;

const client = axios.create({
  baseURL: API_BASE_URL,
  timeout: 20000,
  headers: { Accept: 'application/json' },
});

client.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status;
    if (status === 401 && localStorage.getItem(TOKEN_KEY)) {
      // The session expired or was revoked; clear it and let the app redirect.
      localStorage.removeItem(TOKEN_KEY);
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
    }
    return Promise.reject(error);
  },
);

/**
 * Normalises every failure into { status, code, message, fieldErrors } so the UI can rely on one
 * shape. The backend always answers with the same error envelope
 * ({ timestamp, status, code, message, path, fieldErrors }).
 */
export function apiError(error) {
  if (error?.response?.data) {
    const data = error.response.data;
    return {
      status: error.response.status,
      code: data.code || `HTTP_${error.response.status}`,
      message: data.message || 'The request failed.',
      fieldErrors: data.fieldErrors || null,
    };
  }
  if (error?.code === 'ECONNABORTED') {
    return { status: 0, code: 'TIMEOUT', message: 'The request timed out. Is the backend running?', fieldErrors: null };
  }
  return {
    status: 0,
    code: 'NETWORK_ERROR',
    message: error?.message || 'Could not reach the TradeX backend.',
    fieldErrors: null,
  };
}

export default client;
