import client from './client.js';

export const tradesApi = {
  list: (params = {}) => client.get('/api/trades', { params }).then((r) => r.data),
  get: (id) => client.get(`/api/trades/${id}`).then((r) => r.data),
  byRef: (tradeRef) => client.get(`/api/trades/by-ref/${tradeRef}`).then((r) => r.data),

  /** Downloads the CSV as a Blob so the Authorization header is included. */
  exportCsv: async (params = {}) => {
    const response = await client.get('/api/trades/export', {
      params,
      responseType: 'blob',
    });
    return response.data;
  },
};
