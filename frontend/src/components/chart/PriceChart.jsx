import { useEffect, useRef, useState } from 'react';
import { createChart, CrosshairMode } from 'lightweight-charts';
import { formatNumber, formatQuantity } from '../../lib/format.js';
import { epochSeconds, normalizeCandles } from '../../lib/chartData.js';

const COLORS = {
  up: '#00d4aa',
  down: '#ff4757',
  volumeUp: 'rgba(0, 212, 170, 0.35)',
  volumeDown: 'rgba(255, 71, 87, 0.35)',
  text: '#94a3b8',
  grid: 'rgba(42, 53, 72, 0.4)',
  border: '#2a3548',
};

const CHART_OPTIONS = {
  layout: {
    background: { color: 'transparent' },
    textColor: COLORS.text,
    fontFamily: 'Inter, system-ui, sans-serif',
    fontSize: 11,
  },
  grid: {
    vertLines: { color: COLORS.grid },
    horzLines: { color: COLORS.grid },
  },
  crosshair: {
    mode: CrosshairMode.Normal,
    vertLine: { color: '#3b4a63', labelBackgroundColor: '#1a2234' },
    horzLine: { color: '#3b4a63', labelBackgroundColor: '#1a2234' },
  },
  rightPriceScale: { borderColor: COLORS.border, scaleMargins: { top: 0.08, bottom: 0.22 } },
  timeScale: {
    borderColor: COLORS.border,
    timeVisible: true,
    secondsVisible: false,
    rightOffset: 3,
    barSpacing: 8,
  },
  localization: {
    priceFormatter: (price) => formatNumber(price, 2),
  },
};

function pricePoint(candle, chartType) {
  return chartType === 'candles'
    ? { time: candle.time, open: candle.open, high: candle.high, low: candle.low, close: candle.close }
    : { time: candle.time, value: candle.close };
}

function volumePoint(candle) {
  return {
    time: candle.time,
    value: candle.volume,
    color: candle.close >= candle.open ? COLORS.volumeUp : COLORS.volumeDown,
  };
}

function overlayData(values, times) {
  const points = [];
  for (let i = 0; i < times.length; i += 1) {
    const value = values?.[i];
    if (value !== null && value !== undefined) {
      const time = epochSeconds(times[i]);
      if (time !== null && Number.isFinite(Number(value))) {
        points.push({ time, value: Number(value) });
      }
    }
  }
  return points.sort((left, right) => left.time - right.time);
}

/**
 * Candlestick / line / area chart with a volume histogram and price-pane overlays
 * (SMA, EMA, Bollinger Bands). Crosshair values are shown in an accessible legend that is
 * readable without relying on colour.
 */
export default function PriceChart({
  candles = [],
  timeframeCode = '15m',
  chartType = 'candles',
  overlays = [],
  height = 420,
  historyKey = 'default',
  historyVersion = 0,
  liveCandle = null,
  onChartReady,
}) {
  const containerRef = useRef(null);
  const chartRef = useRef(null);
  const seriesRef = useRef({
    priceSeries: null,
    priceSeriesByType: {},
    volumeSeries: null,
    overlaySeries: {},
  });
  const chartTypeRef = useRef(chartType);
  const candleDataRef = useRef([]);
  const historyKeyRef = useRef(null);
  const historyVersionRef = useRef(null);
  const followLatestRef = useRef(true);
  const onChartReadyRef = useRef(onChartReady);
  const [legend, setLegend] = useState(null);

  onChartReadyRef.current = onChartReady;

  // The chart instance belongs to the mounted container, not to changing market data.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return undefined;

    const chart = createChart(container, { ...CHART_OPTIONS, height, width: container.clientWidth });
    chartRef.current = chart;

    const priceSeriesByType = {
      candles: chart.addCandlestickSeries({
        upColor: COLORS.up,
        downColor: COLORS.down,
        visible: chartTypeRef.current === 'candles',
        borderVisible: false,
        wickUpColor: COLORS.up,
        wickDownColor: COLORS.down,
        priceLineVisible: false,
      }),
      line: chart.addLineSeries({
        color: COLORS.up,
        lineWidth: 2,
        visible: chartTypeRef.current === 'line',
      }),
      area: chart.addAreaSeries({
        lineColor: COLORS.up,
        topColor: 'rgba(0, 212, 170, 0.25)',
        bottomColor: 'rgba(0, 212, 170, 0.02)',
        lineWidth: 2,
        visible: chartTypeRef.current === 'area',
      }),
    };

    const priceSeries = priceSeriesByType[chartTypeRef.current];
    const volumeSeries = chart.addHistogramSeries({
      priceFormat: { type: 'volume' },
      priceScaleId: '',
      lastValueVisible: false,
      priceLineVisible: false,
    });
    volumeSeries.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });

    seriesRef.current = {
      priceSeries,
      priceSeriesByType,
      volumeSeries,
      overlaySeries: {},
    };

    const onMove = (param) => {
      if (!param?.time) {
        setLegend(null);
        return;
      }
      const price = param.seriesData.get(seriesRef.current.priceSeries);
      const volume = param.seriesData.get(volumeSeries);
      if (!price) {
        setLegend(null);
        return;
      }
      setLegend({
        time: param.time,
        open: price.open ?? price.value,
        high: price.high ?? price.value,
        low: price.low ?? price.value,
        close: price.close ?? price.value,
        volume: volume?.value,
      });
    };
    chart.subscribeCrosshairMove(onMove);
    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      const lastIndex = candleDataRef.current.length - 1;
      if (range && lastIndex >= 0) {
        followLatestRef.current = range.to >= lastIndex - 1;
      }
    });

    const resize = () => {
      chart.resize(container.clientWidth, height);
    };
    const observer = new ResizeObserver(resize);
    observer.observe(container);

    onChartReadyRef.current?.(chart);

    return () => {
      observer.disconnect();
      chart.unsubscribeCrosshairMove(onMove);
      chart.remove();
      chartRef.current = null;
      seriesRef.current = {
        priceSeries: null,
        priceSeriesByType: {},
        volumeSeries: null,
        overlaySeries: {},
      };
      onChartReadyRef.current?.(null);
    };
    // chartType and height are applied to the mounted chart in separate effects.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const container = containerRef.current;
    if (container) chartRef.current?.resize(container.clientWidth, height);
  }, [height]);

  // All price series are created once; switching type only changes visibility.
  useEffect(() => {
    const { priceSeriesByType } = seriesRef.current;
    if (!chartRef.current || chartTypeRef.current === chartType) return;
    for (const [type, series] of Object.entries(priceSeriesByType)) {
      series.applyOptions({ visible: type === chartType });
    }
    chartTypeRef.current = chartType;
    seriesRef.current.priceSeries = priceSeriesByType[chartType];
  }, [chartType]);

  // Initial history is replaced only when the instrument/timeframe key changes.
  useEffect(() => {
    const { priceSeriesByType, volumeSeries } = seriesRef.current;
    if (
      !volumeSeries ||
      (historyKeyRef.current === historyKey && historyVersionRef.current === historyVersion)
    ) {
      return;
    }
    const normalized = normalizeCandles(candles);
    candleDataRef.current = normalized;
    for (const [type, series] of Object.entries(priceSeriesByType)) {
      series.setData(normalized.map((candle) => pricePoint(candle, type)));
    }
    volumeSeries.setData(normalized.map(volumePoint));
    const resetView = historyKeyRef.current !== historyKey;
    historyKeyRef.current = historyKey;
    historyVersionRef.current = historyVersion;
    if (resetView) {
      followLatestRef.current = true;
      chartRef.current?.timeScale().fitContent();
    }
  }, [candles, historyKey, historyVersion]);

  // Overlay data has its own lifecycle; candle ticks never rebuild these series.
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    Object.values(seriesRef.current.overlaySeries).forEach((entry) => {
      for (const series of Object.values(entry)) {
        if (series && typeof series.setData === 'function') chart.removeSeries(series);
      }
    });
    const nextOverlaySeries = {};
    for (const overlay of overlays) {
      if (overlay.kind === 'bands') {
        const upper = chart.addLineSeries({ color: overlay.color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        const middle = chart.addLineSeries({ color: overlay.color, lineWidth: 1, lineStyle: 2, priceLineVisible: false, lastValueVisible: false });
        const lower = chart.addLineSeries({ color: overlay.color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
        upper.setData(overlayData(overlay.upper, overlay.times));
        middle.setData(overlayData(overlay.middle, overlay.times));
        lower.setData(overlayData(overlay.lower, overlay.times));
        nextOverlaySeries[overlay.key] = { upper, middle, lower };
      } else if (overlay.values) {
        const line = chart.addLineSeries({
          color: overlay.color,
          lineWidth: 1,
          priceLineVisible: false,
          lastValueVisible: false,
        });
        line.setData(overlayData(overlay.values, overlay.times));
        nextOverlaySeries[overlay.key] = { line };
      }
    }
    seriesRef.current.overlaySeries = nextOverlaySeries;
  }, [overlays]);

  // Live ticks update only the current/new bar; stale bars are ignored.
  useEffect(() => {
    if (!liveCandle) return;
    const normalized = normalizeCandles([liveCandle])[0];
    const { priceSeriesByType, volumeSeries } = seriesRef.current;
    if (!normalized || !volumeSeries) return;
    const latest = candleDataRef.current[candleDataRef.current.length - 1];
    if (latest && normalized.time < latest.time) return;
    const appending = !latest || normalized.time > latest.time;
    try {
      for (const [type, series] of Object.entries(priceSeriesByType)) {
        series.update(pricePoint(normalized, type));
      }
      volumeSeries.update(volumePoint(normalized));
      if (appending) {
        candleDataRef.current = [...candleDataRef.current, normalized];
        if (followLatestRef.current) chartRef.current?.timeScale().scrollToRealTime();
      } else {
        candleDataRef.current[candleDataRef.current.length - 1] = normalized;
      }
    } catch (error) {
      if (import.meta.env.DEV) console.warn('[chart] live candle update rejected', error);
    }
  }, [liveCandle]);

  const legendOverlays = legend
    ? overlays
        .map((overlay) => {
          const entry = seriesRef.current.overlaySeries?.[overlay.key];
          if (!entry) return null;
          return { label: overlay.label, color: overlay.color };
        })
        .filter(Boolean)
    : [];

  return (
    <div className="relative">
      <div className="pointer-events-none absolute left-2 top-2 z-10 flex flex-col gap-0.5 text-[11px] leading-tight">
        <span className="font-semibold uppercase tracking-wide text-ink-500">{timeframeCode}</span>
        {legend ? (
          <span className="num flex flex-wrap gap-x-2 gap-y-0.5 text-slate-300">
            <span>O {formatNumber(legend.open)}</span>
            <span>H {formatNumber(legend.high)}</span>
            <span>L {formatNumber(legend.low)}</span>
            <span>C {formatNumber(legend.close)}</span>
            {legend.volume !== undefined ? <span>Vol {formatQuantity(legend.volume)}</span> : null}
          </span>
        ) : (
          <span className="num text-ink-500">Move the crosshair for OHLC values</span>
        )}
        {legendOverlays.length ? (
          <span className="num flex flex-wrap gap-x-2">
            {legendOverlays.map((overlay) => (
              <span key={overlay.label} style={{ color: overlay.color }}>
                {overlay.label}
              </span>
            ))}
          </span>
        ) : null}
      </div>
      <div ref={containerRef} style={{ height }} data-testid="price-chart" />
    </div>
  );
}
