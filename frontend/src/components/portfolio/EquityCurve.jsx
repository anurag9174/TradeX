import { useEffect, useRef } from 'react';
import { createChart, CrosshairMode } from 'lightweight-charts';
import { formatMoney } from '../../lib/format.js';

const OPTIONS = {
  layout: {
    background: { color: 'transparent' },
    textColor: '#94a3b8',
    fontFamily: 'Inter, system-ui, sans-serif',
    fontSize: 11,
  },
  grid: {
    vertLines: { color: 'rgba(42, 53, 72, 0.35)' },
    horzLines: { color: 'rgba(42, 53, 72, 0.35)' },
  },
  crosshair: {
    mode: CrosshairMode.Normal,
    vertLine: { color: '#3b4a63', labelBackgroundColor: '#1a2234' },
    horzLine: { color: '#3b4a63', labelBackgroundColor: '#1a2234' },
  },
  rightPriceScale: { borderColor: '#2a3548' },
  timeScale: { borderColor: '#2a3548' },
};

/** Realised equity curve from the performance endpoint (daily granularity). */
export default function EquityCurve({ points = [], height = 260, currency = 'USD' }) {
  const containerRef = useRef(null);
  const chartRef = useRef(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return undefined;
    const chart = createChart(container, {
      ...OPTIONS,
      localization: { priceFormatter: (price) => formatMoney(price, currency) },
      height,
      width: container.clientWidth,
    });
    chartRef.current = chart;
    const series = chart.addAreaSeries({
      lineColor: '#00d4aa',
      topColor: 'rgba(0, 212, 170, 0.28)',
      bottomColor: 'rgba(0, 212, 170, 0.02)',
      lineWidth: 2,
      priceLineVisible: false,
    });
    series.setData(
      points
        .filter((point) => point.portfolioValue !== null && point.portfolioValue !== undefined)
        .map((point) => ({ time: point.date, value: Number(point.portfolioValue) })),
    );
    chart.timeScale().fitContent();

    const resize = () => chart.applyOptions({ width: container.clientWidth });
    const observer = new ResizeObserver(resize);
    observer.observe(container);
    return () => {
      observer.disconnect();
      chart.remove();
      chartRef.current = null;
    };
  }, [points, height, currency]);

  return <div ref={containerRef} style={{ height }} data-testid="equity-curve" />;
}
