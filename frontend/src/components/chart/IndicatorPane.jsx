import { useEffect, useRef } from 'react';
import { createChart, CrosshairMode } from 'lightweight-charts';
import { formatNumber } from '../../lib/format.js';

const OPTIONS = {
  layout: {
    background: { color: 'transparent' },
    textColor: '#94a3b8',
    fontFamily: 'Inter, system-ui, sans-serif',
    fontSize: 10,
  },
  grid: {
    vertLines: { color: 'rgba(42, 53, 72, 0.3)' },
    horzLines: { color: 'rgba(42, 53, 72, 0.3)' },
  },
  crosshair: {
    mode: CrosshairMode.Normal,
    vertLine: { color: '#3b4a63', labelBackgroundColor: '#1a2234' },
    horzLine: { color: '#3b4a63', labelBackgroundColor: '#1a2234' },
  },
  rightPriceScale: { borderColor: '#2a3548' },
  timeScale: { borderColor: '#2a3548', timeVisible: true, secondsVisible: false },
  localization: { priceFormatter: (price) => formatNumber(price, 2) },
};

function toTime(value) {
  return typeof value === 'number' ? value : Math.floor(new Date(value).getTime() / 1000);
}

function lineData(values, times) {
  const points = [];
  for (let i = 0; i < times.length; i += 1) {
    const value = values?.[i];
    if (value !== null && value !== undefined) {
      points.push({ time: toTime(times[i]), value: Number(value) });
    }
  }
  return points;
}

/**
 * Secondary indicator pane (RSI or MACD) rendered as its own chart so the price pane stays
 * uncluttered. Its time scale is synchronised with the main chart in both directions.
 */
export default function IndicatorPane({ title, series, mainChart, height = 130 }) {
  const containerRef = useRef(null);
  const chartRef = useRef(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return undefined;
    const chart = createChart(container, { ...OPTIONS, height, width: container.clientWidth });
    chartRef.current = chart;

    const priceSeries =
      series.pane === 'macd'
        ? chart.addLineSeries({ color: '#48dbfb', lineWidth: 2, priceLineVisible: false, lastValueVisible: true })
        : chart.addLineSeries({ color: '#f6ad55', lineWidth: 2, priceLineVisible: false, lastValueVisible: true });

    const extraSeries = [];
    if (series.pane === 'macd') {
      const histogram = chart.addHistogramSeries({ priceLineVisible: false, lastValueVisible: false });
      const signal = chart.addLineSeries({ color: '#ff9ff3', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
      extraSeries.push({ key: 'histogram', api: histogram }, { key: 'signal', api: signal });
    } else {
      priceSeries.createPriceLine({ price: 70, color: 'rgba(255, 71, 87, 0.6)', lineWidth: 1, lineStyle: 2, axisLabelVisible: false, title: '70' });
      priceSeries.createPriceLine({ price: 30, color: 'rgba(0, 212, 170, 0.6)', lineWidth: 1, lineStyle: 2, axisLabelVisible: false, title: '30' });
    }

    priceSeries.setData(lineData(series.values, series.times));
    for (const extra of extraSeries) {
      if (extra.key === 'signal') {
        extra.api.setData(lineData(series.signal, series.times));
      } else {
        const histogram = [];
        for (let i = 0; i < series.times.length; i += 1) {
          const value = series.histogram?.[i];
          if (value !== null && value !== undefined) {
            histogram.push({
              time: toTime(series.times[i]),
              value: Number(value),
              color: Number(value) >= 0 ? 'rgba(0, 212, 170, 0.5)' : 'rgba(255, 71, 87, 0.5)',
            });
          }
        }
        extra.api.setData(histogram);
      }
    }
    chart.timeScale().fitContent();

    // Two-way time-scale sync with the main chart, guarded against feedback loops.
    let syncing = false;
    const fromPane = (range) => {
      if (syncing || !range || !mainChart) return;
      syncing = true;
      try {
        mainChart.timeScale().setVisibleLogicalRange(range);
      } catch {
        /* ignore transient ranges */
      }
      syncing = false;
    };
    const fromMain = (range) => {
      if (syncing || !range) return;
      syncing = true;
      try {
        chart.timeScale().setVisibleLogicalRange(range);
      } catch {
        /* ignore transient ranges */
      }
      syncing = false;
    };
    chart.timeScale().subscribeVisibleLogicalRangeChange(fromPane);
    mainChart?.timeScale().subscribeVisibleLogicalRangeChange(fromMain);

    const resize = () => chart.applyOptions({ width: container.clientWidth });
    const observer = new ResizeObserver(resize);
    observer.observe(container);

    return () => {
      observer.disconnect();
      chart.timeScale().unsubscribeVisibleLogicalRangeChange(fromPane);
      mainChart?.timeScale().unsubscribeVisibleLogicalRangeChange(fromMain);
      chart.remove();
      chartRef.current = null;
    };
  }, [series, mainChart, height]);

  return (
    <div>
      <p className="px-2 pt-1 text-[11px] font-semibold uppercase tracking-wide text-ink-500">{title}</p>
      <div ref={containerRef} style={{ height }} data-testid={`indicator-pane-${series.pane}`} />
    </div>
  );
}
