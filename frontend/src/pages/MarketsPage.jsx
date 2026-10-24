import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { marketApi } from '../api/market.js';
import { apiError } from '../api/client.js';
import { useMarket } from '../context/MarketContext.jsx';
import ChartToolbar from '../components/chart/ChartToolbar.jsx';
import PriceChart from '../components/chart/PriceChart.jsx';
import IndicatorPane from '../components/chart/IndicatorPane.jsx';
import OrderPanel from '../components/orders/OrderPanel.jsx';
import Spinner from '../components/ui/Spinner.jsx';
import ErrorState from '../components/ui/ErrorState.jsx';
import DataSourceBadge from '../components/ui/DataSourceBadge.jsx';
import PriceDelta from '../components/ui/PriceDelta.jsx';
import { formatMoney, formatRelative } from '../lib/format.js';
import {
  epochSeconds,
  epochMilliseconds,
  normalizeCandles,
  updateCandleFromPrice,
  updateCandleFromTrade,
} from '../lib/chartData.js';

const INDICATOR_OPTIONS = [
  { key: 'sma:20', label: 'SMA 20', description: 'Simple moving average, 20 periods', spec: 'sma:20', pane: 'price' },
  { key: 'ema:50', label: 'EMA 50', description: 'Exponential moving average, 50 periods', spec: 'ema:50', pane: 'price' },
  { key: 'bb:20-2', label: 'Bollinger', description: 'Bollinger Bands (20, 2σ)', spec: 'bb:20-2', pane: 'price' },
  { key: 'rsi:14', label: 'RSI 14', description: 'Relative Strength Index, 14 periods', spec: 'rsi:14', pane: 'rsi' },
  { key: 'macd:12-26-9', label: 'MACD', description: 'MACD (12, 26, 9)', spec: 'macd:12-26-9', pane: 'macd' },
];

const OVERLAY_COLORS = {
  'sma:20': '#48dbfb',
  'ema:50': '#f6ad55',
  'bb:20-2': '#a78bfa',
};

const FALLBACK_POLL_MS = 5000;
const LIVE_FRESH_MS = 15000;
const STALE_AFTER_MS = 60000;

function eventTimestamp(event) {
  return epochMilliseconds(
    event?.timestamp ?? event?.time ?? event?.created_at ?? event?.updated_at,
  );
}

function tickerPrice(event) {
  for (const key of ['close', 'last_price', 'lastPrice', 'price']) {
    const value = Number(event?.[key]);
    if (Number.isFinite(value) && value > 0) return value;
  }
  return null;
}

export default function MarketsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [instruments, setInstruments] = useState([]);
  const [timeframe, setTimeframe] = useState('15m');
  const [chartType, setChartType] = useState('candles');
  const [activeIndicators, setActiveIndicators] = useState(['sma:20', 'ema:50']);
  const [series, setSeries] = useState(null);
  const [indicatorData, setIndicatorData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [mainChart, setMainChart] = useState(null);
  const [historyRevision, setHistoryRevision] = useState(0);
  const [liveCandle, setLiveCandle] = useState(null);
  const [liveQuote, setLiveQuote] = useState(null);
  const [deltaConnection, setDeltaConnection] = useState('disabled');
  const [freshnessNow, setFreshnessNow] = useState(Date.now());
  const { quotes, status, subscribeDelta } = useMarket();
  const symbol = (searchParams.get('symbol') || 'RELIANCE').toUpperCase();
  const setSymbol = (next) => setSearchParams({ symbol: next }, { replace: true });
  const candleRef = useRef(null);
  const liveCandleRef = useRef(null);
  const liveCandleKeyRef = useRef(null);
  const candleBucketAnchorRef = useRef(0);
  const seriesRef = useRef(null);
  const quotesRef = useRef(quotes);
  const timeframeRef = useRef(timeframe);
  const symbolRef = useRef(symbol);
  const lastMarketTimeRef = useRef(0);
  const lastAcceptedMarketAtRef = useRef(0);
  const channelMarketTimesRef = useRef({ ticker: 0, trade: 0 });
  const seenTradeIdsRef = useRef(new Set());
  const candleRequestRef = useRef(0);
  const indicatorRequestRef = useRef(0);
  const fallbackInFlightRef = useRef(false);
  seriesRef.current = series;
  quotesRef.current = quotes;
  timeframeRef.current = timeframe;
  symbolRef.current = symbol;

  useEffect(() => {
    marketApi
      .instruments()
      .then(setInstruments)
      .catch(() => setInstruments([]));
  }, []);

  const loadCandles = useCallback(
    async ({ replaceHistory = false } = {}) => {
      const requestId = ++candleRequestRef.current;
      try {
        const data = await marketApi.candles(symbol, timeframe, 500);
        if (requestId !== candleRequestRef.current) return;
        setSeries(data);
        const historyLatest = normalizeCandles(data.candles).at(-1) || null;
        candleBucketAnchorRef.current = historyLatest?.time ?? 0;
        const requestKey = `${symbol}:${timeframe}`;
        const streamedLatest =
          liveCandleKeyRef.current === requestKey ? liveCandleRef.current : null;
        const latest =
          streamedLatest && (!historyLatest || streamedLatest.time >= historyLatest.time)
            ? streamedLatest
            : historyLatest;
        candleRef.current = latest;
        setLiveCandle(streamedLatest === latest ? streamedLatest : null);
        setError(null);
        if (replaceHistory) setHistoryRevision((current) => current + 1);
      } catch (failure) {
        if (requestId === candleRequestRef.current) setError(apiError(failure));
      } finally {
        if (requestId === candleRequestRef.current) setLoading(false);
      }
    },
    [symbol, timeframe],
  );

  const loadIndicators = useCallback(async () => {
    if (!activeIndicators.length) {
      setIndicatorData(null);
      return;
    }
    const requestId = ++indicatorRequestRef.current;
    try {
      const specs = activeIndicators
        .map((key) => INDICATOR_OPTIONS.find((option) => option.key === key)?.spec)
        .filter(Boolean);
      const data = await marketApi.indicators(symbol, timeframe, 500, specs);
      if (requestId === indicatorRequestRef.current) setIndicatorData(data);
    } catch {
      if (requestId === indicatorRequestRef.current) setIndicatorData(null);
    }
  }, [symbol, timeframe, activeIndicators]);

  useEffect(() => {
    candleRef.current = null;
    liveCandleRef.current = null;
    liveCandleKeyRef.current = null;
    candleBucketAnchorRef.current = 0;
    setLiveCandle(null);
    setLiveQuote(null);
    lastMarketTimeRef.current = 0;
    lastAcceptedMarketAtRef.current = 0;
    channelMarketTimesRef.current = { ticker: 0, trade: 0 };
    seenTradeIdsRef.current.clear();
    loadCandles();
    return () => {
      candleRequestRef.current += 1;
    };
  }, [loadCandles]);

  useEffect(() => {
    loadIndicators();
    return () => {
      indicatorRequestRef.current += 1;
    };
  }, [loadIndicators]);

  const applyLiveCandle = useCallback((next) => {
    if (!next) return;
    const current = candleRef.current;
    if (current && next.time < current.time) return;
    candleRef.current = next;
    liveCandleRef.current = next;
    liveCandleKeyRef.current = `${symbolRef.current}:${timeframeRef.current}`;
    setLiveCandle(next);
  }, []);

  const isDeltaLive =
    status?.dataSource === 'LIVE' && String(status?.provider || '').toUpperCase() === 'DELTA';
  const selectedInstrument = instruments.find((instrument) => instrument.symbol === symbol);
  const deltaStreamEnabled = isDeltaLive && selectedInstrument?.providerSupported === true;

  useEffect(() => {
    if (!deltaStreamEnabled) {
      setDeltaConnection('disabled');
      return undefined;
    }
    setDeltaConnection('reconnecting');
    const acceptMarketEvent = (event, channel) => {
      const timestampMilliseconds = eventTimestamp(event);
      if (timestampMilliseconds === null) return null;
      const now = Date.now();
      if (timestampMilliseconds > now + 10000 || now - timestampMilliseconds > 60000) return null;
      if (timestampMilliseconds < channelMarketTimesRef.current[channel]) return null;
      channelMarketTimesRef.current[channel] = timestampMilliseconds;
      lastMarketTimeRef.current = Math.max(
        lastMarketTimeRef.current,
        Math.floor(timestampMilliseconds / 1000),
      );
      lastAcceptedMarketAtRef.current = Date.now();
      return timestampMilliseconds;
    };
    const updateLiveQuote = (price, timestamp) => {
      if (!Number.isFinite(price) || price <= 0) return;
      const asOf = new Date(timestamp).toISOString();
      setLiveQuote((current) => {
        const currentTime = current?.asOf ? Date.parse(current.asOf) : 0;
        return timestamp < currentTime
          ? current
          : {
              ...(current?.symbol === symbol ? current : {}),
              symbol,
              currency: quotesRef.current[symbol]?.currency || seriesRef.current?.currency || 'USD',
              lastPrice: price,
              asOf,
              source: 'LIVE',
              sourceLabel: 'LIVE - Delta Exchange public market data',
              priceAvailable: true,
              available: true,
              change: current?.change ?? quotesRef.current[symbol]?.change ?? null,
              changePercent: current?.changePercent ?? quotesRef.current[symbol]?.changePercent ?? null,
            };
      });
    };
    const cancel = subscribeDelta(symbol, {
      onStatus: setDeltaConnection,
      onTicker: (event) => {
        const price = tickerPrice(event);
        if (price === null) return;
        const timestamp = acceptMarketEvent(event, 'ticker');
        if (timestamp === null) return;
        updateLiveQuote(price, timestamp);
        applyLiveCandle(
          updateCandleFromPrice(
            candleRef.current,
            price,
            timestamp,
            timeframeRef.current,
            candleBucketAnchorRef.current,
          ),
        );
      },
      onTrade: (event) => {
        const timestamp = eventTimestamp(event);
        const price = tickerPrice(event);
        if (timestamp === null || price === null) return;
        const tradeId =
          event.id ??
          event.trade_id ??
          [
            event.product_id ?? event.symbol ?? symbol,
            event.timestamp ?? event.time ?? event.created_at ?? timestamp,
            event.price,
            event.size ?? event.quantity ?? event.volume,
          ].join(':');
        const id = String(tradeId);
        if (seenTradeIdsRef.current.has(id)) return;
        seenTradeIdsRef.current.add(id);
        if (seenTradeIdsRef.current.size > 2000) {
          seenTradeIdsRef.current = new Set([...seenTradeIdsRef.current].slice(-1000));
        }
        const acceptedTimestamp = acceptMarketEvent(event, 'trade');
        if (acceptedTimestamp === null) return;
        updateLiveQuote(price, acceptedTimestamp);
        applyLiveCandle(
          updateCandleFromTrade(
            candleRef.current,
            { ...event, timestamp: acceptedTimestamp },
            timeframeRef.current,
            candleBucketAnchorRef.current,
          ),
        );
      },
    });
    return () => {
      cancel();
      lastMarketTimeRef.current = 0;
      lastAcceptedMarketAtRef.current = 0;
      channelMarketTimesRef.current = { ticker: 0, trade: 0 };
      seenTradeIdsRef.current.clear();
    };
  }, [applyLiveCandle, deltaStreamEnabled, subscribeDelta, symbol]);

  useEffect(() => {
    if (!deltaStreamEnabled) return undefined;
    let cancelled = false;
    const pollLatest = async () => {
      if (
        deltaConnection === 'connected' &&
        Date.now() - lastAcceptedMarketAtRef.current <= LIVE_FRESH_MS
      ) {
        return;
      }
      if (fallbackInFlightRef.current) return;
      fallbackInFlightRef.current = true;
      try {
        const [quoteData, candleData] = await Promise.all([
          marketApi.quote(symbol),
          marketApi.candles(symbol, timeframe, 10),
        ]);
        if (cancelled || quoteData?.dataSource !== 'LIVE') return;
        const timestamp = epochSeconds(quoteData.asOf);
        const price = Number(quoteData.lastPrice);
        if (timestamp !== null && Number.isFinite(price) && price > 0) {
          const now = Math.floor(Date.now() / 1000);
          if (timestamp <= now + 10 && now - timestamp <= 60 && timestamp >= lastMarketTimeRef.current) {
            lastMarketTimeRef.current = timestamp;
            lastAcceptedMarketAtRef.current = Date.now();
            setLiveQuote((current) => {
              const currentTime = current?.asOf ? epochSeconds(current.asOf) : 0;
              return timestamp < currentTime
                ? current
                : { ...quoteData, source: 'LIVE', asOf: quoteData.asOf };
            });
            applyLiveCandle(
              updateCandleFromPrice(
                candleRef.current,
                price,
                timestamp,
                timeframe,
                candleBucketAnchorRef.current,
              ),
            );
          }
        }
        const candles = normalizeCandles(candleData?.candles);
        const newest = candles.at(-1);
        if (
          candleData?.dataSource === 'LIVE' &&
          newest &&
          (!candleRef.current || newest.time > candleRef.current.time)
        ) {
          applyLiveCandle(newest);
        }
      } catch (failure) {
        if (import.meta.env.DEV) console.debug('[market] REST fallback unavailable', failure);
      } finally {
        fallbackInFlightRef.current = false;
      }
    };
    pollLatest();
    const interval = window.setInterval(pollLatest, FALLBACK_POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [applyLiveCandle, deltaConnection, deltaStreamEnabled, symbol, timeframe]);

  useEffect(() => {
    const interval = window.setInterval(() => setFreshnessNow(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, []);

  const overlays = useMemo(() => {
    const result = [];
    if (!indicatorData?.series) return result;
    for (const key of activeIndicators) {
      const seriesData = indicatorData.series[key];
      if (!seriesData) continue;
      const option = INDICATOR_OPTIONS.find((item) => item.key === key);
      if (option?.pane === 'price') {
        result.push({
          key,
          label: seriesData.label || option.label,
          color: OVERLAY_COLORS[key] || '#a78bfa',
          kind: seriesData.kind === 'band' ? 'bands' : 'line',
          values: seriesData.values,
          upper: seriesData.upper,
          middle: seriesData.middle,
          lower: seriesData.lower,
          times: indicatorData.times,
        });
      }
    }
    return result;
  }, [indicatorData, activeIndicators]);

  const historyMatchesSelection =
    series?.symbol === symbol && series?.timeframeCode === timeframe;
  const rsiSeries = indicatorData?.series?.['rsi:14'];
  const macdSeries = indicatorData?.series?.['macd:12-26-9'];
  const streamQuote =
    historyMatchesSelection && liveQuote?.symbol === symbol ? liveQuote : null;
  const sharedQuote = historyMatchesSelection ? quotes[symbol] : null;
  const streamQuoteTime = streamQuote?.asOf ? epochSeconds(streamQuote.asOf) : 0;
  const sharedQuoteTime = sharedQuote?.asOf ? epochSeconds(sharedQuote.asOf) : 0;
  const quote = streamQuoteTime >= sharedQuoteTime ? streamQuote || sharedQuote : sharedQuote;
  const quoteTime = quote?.asOf ? new Date(quote.asOf).getTime() : NaN;
  const quoteAge = freshnessNow - quoteTime;
  const quoteTimestampValid = Number.isFinite(quoteAge) && quoteAge >= -10000;
  const freshness =
    !historyMatchesSelection
      ? 'STALE'
      : series?.dataSource !== 'LIVE'
      ? 'SIMULATED'
      : quoteTimestampValid && quoteAge <= LIVE_FRESH_MS
        ? 'LIVE'
        : quoteTimestampValid && quoteAge <= STALE_AFTER_MS
          ? 'DELAYED'
          : deltaStreamEnabled && deltaConnection !== 'connected'
            ? 'DISCONNECTED'
            : 'STALE';
  const lastUpdate = Number.isFinite(quoteTime)
    ? quote.asOf
    : series?.generatedAt || null;
  const chartHistoryKey = series
    ? `${series.symbol}:${series.timeframeCode}`
    : `${symbol}:${timeframe}:loading`;
  const historicalCandles = series?.candles || [];
  const historyLatestTime = historicalCandles.length
    ? epochSeconds(
        historicalCandles[historicalCandles.length - 1].time ??
          historicalCandles[historicalCandles.length - 1].openTime,
      )
    : null;
  const liveCandleAddsBar = liveCandle && historyLatestTime !== null && liveCandle.time > historyLatestTime;
  const candleCount = historicalCandles.length + (liveCandleAddsBar ? 1 : 0);

  const toggleIndicator = (key) => {
    setActiveIndicators((current) =>
      current.includes(key) ? current.filter((item) => item !== key) : [...current, key],
    );
  };

  if (loading && !series) {
    return <Spinner label={`Loading ${symbol} candles…`} />;
  }

  if (error && !series) {
    return <ErrorState message={error.message} onRetry={() => loadCandles()} />;
  }

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-semibold text-slate-100">{symbol}</h1>
            <DataSourceBadge
              source={historyMatchesSelection ? series?.dataSource : null}
              label={historyMatchesSelection ? series?.dataSourceLabel : null}
            />
          </div>
          <p className="mt-0.5 text-sm text-ink-500">
            {historyMatchesSelection ? series?.name : 'Loading selected market history…'}
          </p>
        </div>
        <div className="flex items-center gap-4">
          {quote?.lastPrice != null ? (
            <div className="text-right">
              <p className="num text-lg font-semibold text-slate-100">{formatMoney(quote.lastPrice, quote.currency)}</p>
              <PriceDelta change={quote.change} changePercent={quote.changePercent} className="text-xs" />
            </div>
          ) : null}
          <button
            type="button"
            className="btn-outline"
            onClick={() => {
              loadCandles({ replaceHistory: true });
              loadIndicators();
            }}
            title="Refresh candles"
          >
            <RefreshCw size={14} aria-hidden="true" />
            Refresh
          </button>
        </div>
      </header>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
        <section className="panel overflow-hidden" aria-label={`${symbol} chart`}>
          <ChartToolbar
            chartType={chartType}
            onChartType={setChartType}
            timeframe={timeframe}
            onTimeframe={setTimeframe}
            symbol={symbol}
            candleBucketAnchor={historyMatchesSelection ? historyLatestTime ?? 0 : 0}
            indicatorOptions={INDICATOR_OPTIONS}
            activeIndicators={activeIndicators}
            onToggleIndicator={toggleIndicator}
          />
          <PriceChart
            candles={series?.candles || []}
            timeframeCode={series?.timeframeCode || timeframe}
            chartType={chartType}
            overlays={overlays}
            height={430}
            historyKey={chartHistoryKey}
            historyVersion={historyRevision}
            liveCandle={liveCandle}
            onChartReady={setMainChart}
          />
          {rsiSeries ? (
            <div className="border-t border-ink-700">
              <IndicatorPane title="RSI (14)" series={{ ...rsiSeries, times: indicatorData.times }} mainChart={mainChart} />
            </div>
          ) : null}
          {macdSeries ? (
            <div className="border-t border-ink-700">
              <IndicatorPane title="MACD (12, 26, 9)" series={{ ...macdSeries, times: indicatorData.times }} mainChart={mainChart} />
            </div>
          ) : null}
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-ink-700 px-3 py-2 text-[11px] text-ink-500">
            <span>
              {historyMatchesSelection ? candleCount : 'Loading'} candles ·{' '}
              {historyMatchesSelection && (series?.live || liveCandle)
                ? 'latest bar forming'
                : 'closed bars'}
              {historyMatchesSelection && series?.note ? ` · ${series.note}` : ''}
            </span>
            <span className="flex flex-wrap items-center gap-2">
              <span
                className={
                  freshness === 'LIVE'
                    ? 'text-accent'
                    : freshness === 'DELAYED'
                      ? 'text-warning'
                      : freshness === 'STALE' || freshness === 'DISCONNECTED'
                        ? 'text-danger-soft'
                        : 'text-ink-500'
                }
                data-testid="market-freshness"
              >
                {freshness}
              </span>
              {deltaStreamEnabled ? <span>{deltaConnection.toUpperCase()}</span> : null}
              {status?.statusLabel ? `${status.statusLabel} · ` : ''}
              {lastUpdate ? `updated ${formatRelative(lastUpdate)}` : 'waiting for market data'}
            </span>
          </div>
        </section>

        <OrderPanel
          symbols={instruments}
          selectedSymbol={symbol}
          onSymbolChange={setSymbol}
          quote={quote || (historyMatchesSelection ? series : null)}
        />
      </div>
    </div>
  );
}
