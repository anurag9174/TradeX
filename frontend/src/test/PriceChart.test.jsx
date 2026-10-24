import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import PriceChart from '../components/chart/PriceChart.jsx';
import { chartInstances, resetChartInstances } from './lightweightChartsMock.js';

const HISTORY = [
  { time: 60, open: 10, high: 12, low: 9, close: 11, volume: 4 },
  { time: 120, open: 11, high: 12, low: 10, close: 11, volume: 5 },
];

describe('PriceChart lifecycle and live updates', () => {
  beforeEach(resetChartInstances);
  afterEach(cleanup);

  it('keeps one chart, avoids historical replacement on ticks, and removes only on unmount', () => {
    const view = render(
      <PriceChart candles={HISTORY} historyKey="BTCUSD:1m" liveCandle={HISTORY[1]} />,
    );
    const chart = chartInstances[0];
    const initialSetDataCalls = chart.series.reduce((total, series) => total + series.dataSets.length, 0);
    expect(chartInstances).toHaveLength(1);
    expect(chart.fitCalls).toBe(1);

    view.rerender(
      <PriceChart
        candles={HISTORY}
        historyKey="BTCUSD:1m"
        liveCandle={{ ...HISTORY[1], close: 11.5, high: 11.5 }}
      />,
    );

    expect(chartInstances).toHaveLength(1);
    expect(chart.removeCalls).toBe(0);
    expect(chart.fitCalls).toBe(1);
    expect(chart.series.reduce((total, series) => total + series.dataSets.length, 0)).toBe(initialSetDataCalls);
    expect(chart.series.find((series) => series.type === 'candles').updates.at(-1).close).toBe(11.5);

    view.unmount();
    expect(chart.removeCalls).toBe(1);
  });

  it('preserves the viewport on historical refresh and rejects older candles', () => {
    const view = render(
      <PriceChart candles={HISTORY} historyKey="BTCUSD:1m" historyVersion={0} />,
    );
    const chart = chartInstances[0];
    const candleSeries = chart.series.find((series) => series.type === 'candles');
    const initialSets = candleSeries.dataSets.length;

    view.rerender(
      <PriceChart candles={HISTORY} historyKey="BTCUSD:1m" historyVersion={1} />,
    );
    expect(candleSeries.dataSets).toHaveLength(initialSets + 1);
    expect(chart.fitCalls).toBe(1);

    view.rerender(
      <PriceChart
        candles={HISTORY}
        historyKey="BTCUSD:1m"
        historyVersion={1}
        liveCandle={{ time: 60, open: 9, high: 10, low: 8, close: 9, volume: 2 }}
      />,
    );
    expect(candleSeries.updates).toHaveLength(0);
    expect(chart.fitCalls).toBe(1);
  });
});
