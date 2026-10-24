const TIMEFRAME_SECONDS = {
  '1m': 60,
  '5m': 300,
  '15m': 900,
  '30m': 1800,
  '1h': 3600,
  '4h': 14400,
  '1d': 86400,
  '1w': 604800,
};

export function epochMilliseconds(value) {
  if (typeof value === 'number') {
    if (value > 1e17) return Math.floor(value / 1e6);
    if (value > 1e14) return Math.floor(value / 1e3);
    return Math.floor(value > 1e12 ? value : value * 1000);
  }
  if (typeof value === 'string') {
    const numeric = Number(value);
    if (Number.isFinite(numeric)) {
      if (numeric > 1e17) return Math.floor(numeric / 1e6);
      if (numeric > 1e14) return Math.floor(numeric / 1e3);
      return Math.floor(numeric > 1e12 ? numeric : numeric * 1000);
    }
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

export function epochSeconds(value) {
  const milliseconds = epochMilliseconds(value);
  return milliseconds === null ? null : Math.floor(milliseconds / 1000);
}

export function normalizeCandle(candle) {
  const time = epochSeconds(candle?.time ?? candle?.openTime);
  const open = Number(candle?.open);
  const high = Number(candle?.high);
  const low = Number(candle?.low);
  const close = Number(candle?.close);
  const volume = Number(candle?.volume ?? 0);
  if (
    time === null ||
    ![open, high, low, close, volume].every(Number.isFinite) ||
    Math.min(open, high, low, close) <= 0 ||
    high < Math.max(open, close, low) ||
    low > Math.min(open, close, high) ||
    volume < 0
  ) {
    return null;
  }
  return { time, open, high, low, close, volume };
}

export function normalizeCandles(candles = []) {
  const unique = new Map();
  for (const candle of candles) {
    const normalized = normalizeCandle(candle);
    if (normalized) unique.set(normalized.time, normalized);
  }
  return [...unique.values()].sort((left, right) => left.time - right.time);
}

export function candleBucket(timestamp, timeframe, bucketAnchor = 0) {
  const interval = TIMEFRAME_SECONDS[timeframe];
  if (!interval) return null;
  const anchor = Number.isFinite(bucketAnchor) ? bucketAnchor : 0;
  return anchor + Math.floor((timestamp - anchor) / interval) * interval;
}

export function candleTiming(nowMilliseconds, timeframe, bucketAnchor = 0) {
  const interval = TIMEFRAME_SECONDS[timeframe];
  if (!interval || !Number.isFinite(nowMilliseconds)) return null;
  const nowSeconds = Math.floor(nowMilliseconds / 1000);
  const openTime = candleBucket(nowSeconds, timeframe, bucketAnchor);
  const closeTime = openTime + interval;
  return {
    openTime,
    closeTime,
    remainingSeconds: Math.max(0, Math.ceil((closeTime * 1000 - nowMilliseconds) / 1000)),
  };
}

export function updateCandleFromTrade(current, trade, timeframe, bucketAnchor = 0) {
  const timestampMilliseconds = epochMilliseconds(
    trade?.timestamp ?? trade?.time ?? trade?.created_at,
  );
  const timestamp = timestampMilliseconds === null ? null : Math.floor(timestampMilliseconds / 1000);
  const price = Number(trade?.price);
  const volume = Number(trade?.size ?? trade?.quantity ?? trade?.volume);
  if (timestamp === null || !Number.isFinite(price) || price <= 0 || !Number.isFinite(volume) || volume < 0) {
    return null;
  }
  const time = candleBucket(timestamp, timeframe, bucketAnchor);
  if (time === null || (current && time < current.time)) return null;
  if (!current || time > current.time) {
    return {
      time,
      open: price,
      high: price,
      low: price,
      close: price,
      volume,
      lastEventTime: timestampMilliseconds,
    };
  }
  const lastEventTime = current.lastEventTime ?? 0;
  return {
    time,
    open: current.open,
    high: Math.max(current.high, price),
    low: Math.min(current.low, price),
    close: timestampMilliseconds >= lastEventTime ? price : current.close,
    volume: current.volume + volume,
    lastEventTime: Math.max(lastEventTime, timestampMilliseconds),
  };
}

export function updateCandleFromPrice(current, price, timestamp, timeframe, bucketAnchor = 0) {
  const timeMilliseconds = epochMilliseconds(timestamp);
  const timeSeconds = timeMilliseconds === null ? null : Math.floor(timeMilliseconds / 1000);
  const value = Number(price);
  if (timeSeconds === null || !Number.isFinite(value) || value <= 0) return null;
  const time = candleBucket(timeSeconds, timeframe, bucketAnchor);
  if (time === null || (current && time < current.time)) return null;
  if (!current || time > current.time) {
    return {
      time,
      open: value,
      high: value,
      low: value,
      close: value,
      volume: 0,
      lastEventTime: timeMilliseconds,
    };
  }
  const lastEventTime = current.lastEventTime ?? 0;
  return {
    time,
    open: current.open,
    high: Math.max(current.high, value),
    low: Math.min(current.low, value),
    close: timeMilliseconds >= lastEventTime ? value : current.close,
    volume: current.volume,
    lastEventTime: Math.max(lastEventTime, timeMilliseconds),
  };
}
