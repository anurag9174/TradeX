import { useEffect, useState } from 'react';
import { BarChart2, CandlestickChart, LineChart } from 'lucide-react';
import { candleTiming } from '../../lib/chartData.js';

const TIMEFRAMES = ['1m', '5m', '15m', '30m', '1h', '4h', '1d', '1w'];

const CHART_TYPES = [
  { value: 'candles', label: 'Candles', icon: CandlestickChart },
  { value: 'line', label: 'Line', icon: LineChart },
  { value: 'area', label: 'Area', icon: BarChart2 },
];

export default function ChartToolbar({
  chartType,
  onChartType,
  timeframe,
  onTimeframe,
  indicatorOptions,
  activeIndicators,
  onToggleIndicator,
  symbol,
  candleBucketAnchor = 0,
}) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, []);

  useEffect(() => {
    setNow(Date.now());
  }, [timeframe, symbol, candleBucketAnchor]);

  const timing = candleTiming(now, timeframe, candleBucketAnchor);
  const closingAt = timing?.closeTime ?? 0;
  const remainingSeconds = timing?.remainingSeconds ?? 0;
  const countdown =
    remainingSeconds >= 3600
      ? [Math.floor(remainingSeconds / 3600), Math.floor((remainingSeconds % 3600) / 60), remainingSeconds % 60]
          .map((part) => String(part).padStart(2, '0'))
          .join(':')
      : [Math.floor(remainingSeconds / 60), remainingSeconds % 60]
          .map((part) => String(part).padStart(2, '0'))
          .join(':');
  const closingDate = new Date(closingAt * 1000);
  const localTimezoneTime = timing
    ? new Intl.DateTimeFormat(undefined, {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: true,
        timeZoneName: 'short',
      }).format(closingDate)
    : '—';

  return (
    <div className="flex flex-wrap items-center gap-3 border-b border-ink-700 px-3 py-2">
      <div className="flex items-center gap-1" role="group" aria-label="Chart type">
        {CHART_TYPES.map(({ value, label, icon: Icon }) => (
          <button
            key={value}
            type="button"
            className={`rounded-md px-2 py-1.5 text-xs font-medium ${
              chartType === value ? 'bg-accent/15 text-accent' : 'text-ink-500 hover:text-slate-200'
            }`}
            aria-pressed={chartType === value}
            onClick={() => onChartType(value)}
            title={`${label} chart`}
          >
            <Icon size={15} aria-hidden="true" />
            <span className="sr-only">{label} chart</span>
          </button>
        ))}
      </div>

      <div className="h-5 w-px bg-ink-700" aria-hidden="true" />

      <div className="flex flex-wrap items-center gap-1" role="group" aria-label="Timeframe">
        {TIMEFRAMES.map((tf) => (
          <button
            key={tf}
            type="button"
            className={`rounded-md px-2 py-1 text-xs font-semibold uppercase ${
              timeframe === tf ? 'bg-accent/15 text-accent' : 'text-ink-500 hover:text-slate-200'
            }`}
            aria-pressed={timeframe === tf}
            onClick={() => onTimeframe(tf)}
          >
            {tf}
          </button>
        ))}
      </div>

      <div
        className="num flex flex-col text-[11px] leading-tight text-ink-500"
        aria-label={`${timeframe.toUpperCase()} candle closing timer`}
        data-testid="candle-countdown"
      >
        <span>
          {timeframe.toUpperCase()} · Current candle closes at:{' '}
          <time dateTime={timing ? closingDate.toISOString() : undefined}>{localTimezoneTime}</time>
        </span>
        <span>
          Time remaining: {countdown} · Next candle starts at:{' '}
          <time dateTime={timing ? closingDate.toISOString() : undefined}>{localTimezoneTime}</time>
        </span>
      </div>

      <div className="h-5 w-px bg-ink-700" aria-hidden="true" />

      <div className="flex flex-wrap items-center gap-1" role="group" aria-label="Indicators">
        {indicatorOptions.map((option) => {
          const active = activeIndicators.includes(option.key);
          return (
            <button
              key={option.key}
              type="button"
              className={`rounded-md border px-2 py-1 text-xs font-medium transition-colors ${
                active
                  ? 'border-accent/50 bg-accent/10 text-accent'
                  : 'border-ink-700 text-ink-500 hover:border-ink-600 hover:text-slate-200'
              }`}
              aria-pressed={active}
              onClick={() => onToggleIndicator(option.key)}
              title={option.description}
            >
              {option.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
