import client from './client.js';

export const watchlistApi = {
  list: () => client.get('/api/watchlist').then((r) => r.data),
  add: (symbol) => client.post('/api/watchlist', { symbol }).then((r) => r.data),
  remove: (symbol) =>
    client.delete(`/api/watchlist/${encodeURIComponent(symbol)}`).then((r) => r.data),
  catalogue: () => client.get('/api/watchlist/catalogue').then((r) => r.data),
};
