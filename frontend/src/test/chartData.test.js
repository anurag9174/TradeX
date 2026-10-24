import { describe, expect, it } from 'vitest';
import {
  candleBucket,
  candleTiming,
  epochMilliseconds,
  epochSeconds,
  normalizeCandles,
  updateCandleFromPrice,
  updateCandleFromTrade,
} from '../lib/chartData.js';

describe('chart market-data helpers', () => {
  it('normalizes millisecond timestamps and sorts unique valid candles', () => {
    expect(epochSeconds(1710000000000)).toBe(1710000000);
    expect(epochSeconds(1792830039233024)).toBe(1792830039);
    expect(epochMilliseconds(1792830039233024)).toBe(1792830039233);
    expect(epochSeconds('1792830039233024')).toBe(1792830039);
    expect(epochSeconds(1792830039233000000)).toBe(1792830039);
    expect(
      normalizeCandles([
        { time: 120, open: 2, high: 4, low: 1, close: 3, volume: 7 },
        { time: 60, open: 3, high: 3, low: 3, close: 3, volume: 0 },
        { time: 120, open: 2, high: 3, low: 2, close: 2.5, volume: 8 },
        { time: 180, open: 3, high: 1, low: 2, close: 2, volume: 0 },
      ]),
    ).toEqual([
      { time: 60, open: 3, high: 3, low: 3, close: 3, volume: 0 },
      { time: 120, open: 2, high: 3, low: 2, close: 2.5, volume: 8 },
    ]);
  });

  it('aligns every supported timeframe to UTC interval boundaries', () => {
    const timestamp = Date.UTC(2024, 0, 10, 12, 34, 56) / 1000;
    const expected = {
      '1m': 60,
      '5m': 300,
      '15m': 900,
      '30m': 1800,
      '1h': 3600,
      '4h': 14400,
      '1d': 86400,
    };
    for (const [timeframe, interval] of Object.entries(expected)) {
      expect(candleBucket(timestamp, timeframe) % interval).toBe(0);
    }
    expect(candleBucket(timestamp, '1w')).toBe(Math.floor(timestamp / 604800) * 604800);
  });

  it('uses the latest historical candle as the exchange bucket alignment anchor', () => {
    expect(candleBucket(171, '1m', 17)).toBe(137);
    expect(candleBucket(239, '1m', 17)).toBe(197);
  });

  it('calculates close time, remaining seconds, and boundary rollover for all timeframes', () => {
    const now = Date.UTC(2024, 0, 10, 12, 34, 28);
    const intervals = {
      '1m': 60,
      '5m': 300,
      '15m': 900,
      '30m': 1800,
      '1h': 3600,
      '4h': 14400,
      '1d': 86400,
      '1w': 604800,
    };
    for (const [timeframe, seconds] of Object.entries(intervals)) {
      const timing = candleTiming(now, timeframe);
      expect(timing.closeTime - timing.openTime).toBe(seconds);
      expect(timing.remainingSeconds).toBeGreaterThan(0);
      expect(candleTiming(timing.closeTime * 1000, timeframe).closeTime).toBe(
        timing.closeTime + seconds,
      );
    }
    expect(candleTiming(now, '1m').remainingSeconds).toBe(32);
    expect(candleTiming(now, '1m', 17).closeTime).toBe(
      candleBucket(Math.floor(now / 1000), '1m', 17) + 60,
    );
    expect(candleTiming(now, 'unsupported')).toBeNull();
  });

  it('updates price candles incrementally without inventing volume or old bars', () => {
    const current = { time: 60, open: 10, high: 12, low: 9, close: 11, volume: 4 };
    expect(updateCandleFromPrice(current, 13, 120, '1m')).toEqual({
      time: 120, open: 13, high: 13, low: 13, close: 13, volume: 0, lastEventTime: 120000,
    });
    expect(updateCandleFromPrice(current, 8, 70, '1m')).toEqual({
      time: 60, open: 10, high: 12, low: 8, close: 8, volume: 4, lastEventTime: 70000,
    });
    expect(updateCandleFromPrice(current, 8, 59, '1m')).toBeNull();
  });

  it('aggregates valid trades and rejects trades older than the current bar', () => {
    const current = { time: 60, open: 10, high: 10, low: 10, close: 10, volume: 2 };
    expect(updateCandleFromTrade(current, { timestamp: 90, price: 12, size: 3 }, '1m')).toEqual({
      time: 60, open: 10, high: 12, low: 10, close: 12, volume: 5, lastEventTime: 90000,
    });
    expect(updateCandleFromTrade(current, { timestamp: 59, price: 12, size: 3 }, '1m')).toBeNull();
    expect(updateCandleFromTrade(current, { timestamp: 90, price: 12 }, '1m')).toBeNull();
  });

  it('does not let an older trade move the close backwards while retaining its volume', () => {
    const current = {
      time: 60, open: 10, high: 12, low: 9, close: 12, volume: 4, lastEventTime: 110000,
    };
    expect(updateCandleFromTrade(current, { timestamp: 100, price: 8, size: 2 }, '1m')).toEqual({
      time: 60, open: 10, high: 12, low: 8, close: 12, volume: 6, lastEventTime: 110000,
    });
  });

  it('preserves sub-second ordering of Delta trades within the same candle', () => {
    const later = 1792830155354170;
    const earlier = 1792830153907287;
    const bucket = candleBucket(epochSeconds(later), '1m');
    const current = {
      time: bucket,
      open: 10,
      high: 12,
      low: 9,
      close: 12,
      volume: 4,
      lastEventTime: epochMilliseconds(later),
    };
    const result = updateCandleFromTrade(current, { timestamp: earlier, price: 8, size: 2 }, '1m');
    expect(result.close).toBe(12);
    expect(result.volume).toBe(6);
  });
});
