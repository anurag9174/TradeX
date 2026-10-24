import { useCallback, useEffect, useMemo, useState } from 'react';
import { Plus, RefreshCw } from 'lucide-react';
import { watchlistApi } from '../api/watchlist.js';
import { apiError } from '../api/client.js';
import { useMarket } from '../context/MarketContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import QuoteTable from '../components/market/QuoteTable.jsx';
import Spinner from '../components/ui/Spinner.jsx';
import ErrorState from '../components/ui/ErrorState.jsx';
import DataSourceBadge from '../components/ui/DataSourceBadge.jsx';

export default function WatchlistPage() {
  const toast = useToast();
  const [watchlist, setWatchlist] = useState(null);
  const [catalogue, setCatalogue] = useState([]);
  const [selected, setSelected] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const { quotes } = useMarket();

  const load = useCallback(async () => {
    try {
      const [list, instruments] = await Promise.all([watchlistApi.list(), watchlistApi.catalogue()]);
      setWatchlist(list);
      setCatalogue(instruments);
      setError(null);
    } catch (failure) {
      setError(apiError(failure));
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const merged = useMemo(() => {
    if (!watchlist) return [];
    return watchlist.items.map((item) => {
      const tick = quotes[item.symbol];
      if (!tick) return item;
      return {
        ...item,
        quote: {
          ...item.quote,
          lastPrice: tick.lastPrice ?? item.quote?.lastPrice,
          change: tick.change ?? item.quote?.change,
          changePercent: tick.changePercent ?? item.quote?.changePercent,
          dayHigh: tick.dayHigh ?? item.quote?.dayHigh,
          dayLow: tick.dayLow ?? item.quote?.dayLow,
          priceAvailable: tick.available,
          dataSource: tick.source ?? item.quote?.dataSource,
          dataSourceLabel: tick.sourceLabel ?? item.quote?.dataSourceLabel,
        },
      };
    });
  }, [watchlist, quotes]);

  const add = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      await watchlistApi.add(selected);
      toast.success(`${selected} added to your watchlist.`);
      setSelected('');
      await load();
    } catch (failure) {
      toast.error(apiError(failure).message, 'Could not add');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (symbol) => {
    try {
      await watchlistApi.remove(symbol);
      toast.info(`${symbol} removed from your watchlist.`);
      await load();
    } catch (failure) {
      toast.error(apiError(failure).message, 'Could not remove');
    }
  };

  if (!watchlist && !error) {
    return <Spinner label="Loading your watchlist…" />;
  }
  if (error && !watchlist) {
    return <ErrorState message={error.message} onRetry={load} />;
  }

  const watched = new Set(merged.map((item) => item.symbol));

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-100">Watchlist</h1>
          <p className="mt-0.5 text-sm text-ink-500">
            Live quotes for the instruments you follow. Prices tick over the WebSocket stream.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <DataSourceBadge source={watchlist?.dataSource} label={watchlist?.dataSourceLabel} />
          <button type="button" className="btn-outline" onClick={load}>
            <RefreshCw size={14} aria-hidden="true" />
            Refresh
          </button>
        </div>
      </header>

      <section className="panel" aria-labelledby="add-heading">
        <div className="panel-header">
          <h2 id="add-heading" className="text-sm font-semibold text-slate-100">
            Add an instrument
          </h2>
          <span className="text-[11px] text-ink-500">{merged.length} / 60 watched</span>
        </div>
        <div className="flex flex-wrap items-end gap-2 p-4">
          <div className="min-w-[240px] flex-1">
            <label className="label" htmlFor="watchlist-add">
              Instrument
            </label>
            <select
              id="watchlist-add"
              className="input"
              value={selected}
              onChange={(event) => setSelected(event.target.value)}
            >
              <option value="">Select an instrument…</option>
              {catalogue
                .filter((instrument) => !watched.has(instrument.symbol))
                .map((instrument) => (
                  <option key={instrument.symbol} value={instrument.symbol}>
                    {instrument.symbol} - {instrument.name}
                  </option>
                ))}
            </select>
          </div>
          <button type="button" className="btn-accent" onClick={add} disabled={!selected || busy}>
            <Plus size={15} aria-hidden="true" />
            Add
          </button>
        </div>
      </section>

      <section className="panel overflow-hidden" aria-labelledby="watchlist-table-heading">
        <div className="panel-header">
          <h2 id="watchlist-table-heading" className="text-sm font-semibold text-slate-100">
            Watching
          </h2>
        </div>
        <QuoteTable
          items={merged}
          onRemove={remove}
          emptyMessage="Nothing on your watchlist yet. Add instruments above."
        />
      </section>
    </div>
  );
}
