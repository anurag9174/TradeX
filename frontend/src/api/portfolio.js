import client from './client.js';

export const portfolioApi = {
  portfolio: () => client.get('/api/portfolio').then((r) => r.data),
  holdings: () => client.get('/api/portfolio/holdings').then((r) => r.data),
  performance: (params = {}) =>
    client.get('/api/portfolio/performance', { params }).then((r) => r.data),
};
