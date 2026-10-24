import client from './client.js';

export const ordersApi = {
  place: (payload, idempotencyKey) =>
    client
      .post('/api/orders', payload, {
        headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      })
      .then((r) => r.data),
  estimate: (payload) => client.post('/api/orders/estimate', payload).then((r) => r.data),
  list: (params = {}) => client.get('/api/orders', { params }).then((r) => r.data),
  open: () => client.get('/api/orders/open').then((r) => r.data),
  get: (id) => client.get(`/api/orders/${id}`).then((r) => r.data),
  cancel: (id) => client.delete(`/api/orders/${id}`).then((r) => r.data),
};
