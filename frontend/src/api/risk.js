import client from './client.js';

export const riskApi = {
  summary: () => client.get('/api/risk/summary').then((r) => r.data),
  openPositions: () => client.get('/api/risk/open-positions').then((r) => r.data),
  positionSize: (payload) => client.post('/api/risk/position-size', payload).then((r) => r.data),
  journal: (params = {}) => client.get('/api/risk/journal', { params }).then((r) => r.data),
  addJournal: (payload) => client.post('/api/risk/journal', payload).then((r) => r.data),
  deleteJournal: (id) => client.delete(`/api/risk/journal/${id}`).then((r) => r.data),
  updateSettings: (payload) => client.put('/api/risk/settings', payload).then((r) => r.data),
};
