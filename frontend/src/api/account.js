import client from './client.js';

export const accountApi = {
  account: () => client.get('/api/account').then((r) => r.data),
  summary: () => client.get('/api/account/summary').then((r) => r.data),
  balance: () => client.get('/api/account/balance').then((r) => r.data),
  reset: (payload) => client.post('/api/account/reset', payload).then((r) => r.data),
};
