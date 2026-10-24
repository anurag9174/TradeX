import client from './client.js';

export const marketApi = {
  instruments: (params = {}) =>
    client.get('/api/market/instruments', { params }).then((r) => r.data),
  instrument: (symbol) =>
    client.get(`/api/market/instruments/${encodeURIComponent(symbol)}`).then((r) => r.data),
  quote: (symbol) =>
    client.get(`/api/market/quote/${encodeURIComponent(symbol)}`).then((r) => r.data),
  quotes: (symbols) =>
    client
      .get('/api/market/quotes', { params: { symbols: symbols.join(',') } })
      .then((r) => r.data),
  candles: (symbol, timeframe, limit = 500) =>
    client
      .get(`/api/market/candles/${encodeURIComponent(symbol)}`, {
        params: { timeframe, limit },
      })
      .then((r) => r.data),
  indicators: (symbol, timeframe, limit, indicators) =>
    client
      .get(`/api/market/indicators/${encodeURIComponent(symbol)}`, {
        params: { timeframe, limit, indicators: indicators?.join(',') },
      })
      .then((r) => r.data),
  timeframes: () => client.get('/api/market/timeframes').then((r) => r.data),
  status: () => client.get('/api/market/status').then((r) => r.data),
  providers: () => client.get('/api/market/providers').then((r) => r.data),
};
