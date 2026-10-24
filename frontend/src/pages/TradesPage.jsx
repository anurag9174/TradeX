import { useCallback, useEffect, useState } from 'react';
import { Download, FilterX } from 'lucide-react';
import { tradesApi } from '../api/trades.js';
import { marketApi } from '../api/market.js';
import { apiError } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import Pagination from '../components/ui/Pagination.jsx';
import Spinner from '../components/ui/Spinner.jsx';
import ErrorState from '../components/ui/ErrorState.jsx';
import EmptyState from '../components/ui/EmptyState.jsx';
import { downloadBlob, formatDateTime, formatMoney, formatPercent, formatQuantity } from '../lib/format.js';

const EMPTY_FILTERS = { symbol: '', side: '', from: '', to: '', result: '' };

export default function TradesPage() {
  const toast = useToast();
  const [draft, setDraft] = useState(EMPTY_FILTERS);
  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [instruments, setInstruments] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);

  const load = useCallback(
    async (showSpinner = false) => {
      if (showSpinner) setLoading(true);
      try {
        const params = {
          page,
          size: 20,
          ...(applied.symbol ? { symbol: applied.symbol.toUpperCase() } : {}),
          ...(applied.side ? { side: applied.side } : {}),
          ...(applied.from ? { from: applied.from } : {}),
          ...(applied.to ? { to: applied.to } : {}),
          ...(applied.result ? { result: applied.result } : {}),
        };
        const result = await tradesApi.list(params);
        setData(result);
        setError(null);
      } catch (failure) {
        setError(apiError(failure));
      } finally {
        if (showSpinner) setLoading(false);
      }
    },
    [page, applied],
  );

  useEffect(() => {
    load(true);
  }, [load]);

  useEffect(() => {
    marketApi
      .instruments()
      .then(setInstruments)
      .catch(() => setInstruments([]));
  }, []);

  const exportCsv = async () => {
    setExporting(true);
    try {
      const blob = await tradesApi.exportCsv({
        ...(applied.symbol ? { symbol: applied.symbol.toUpperCase() } : {}),
        ...(applied.side ? { side: applied.side } : {}),
        ...(applied.from ? { from: applied.from } : {}),
        ...(applied.to ? { to: applied.to } : {}),
        ...(applied.result ? { result: applied.result } : {}),
      });
      downloadBlob(blob, `tradex-trades-${new Date().toISOString().slice(0, 10)}.csv`);
      toast.success('CSV exported.');
    } catch (failure) {
      toast.error(apiError(failure).message, 'Export failed');
    } finally {
      setExporting(false);
    }
  };

  const applyFilters = (event) => {
    event.preventDefault();
    setPage(0);
    setApplied(draft);
  };

  const clearFilters = () => {
    setDraft(EMPTY_FILTERS);
    setApplied(EMPTY_FILTERS);
    setPage(0);
  };

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-100">Trade history</h1>
          <p className="mt-0.5 text-sm text-ink-500">
            Every executed trade with its charges and realised P&amp;L. Trades are immutable.
          </p>
        </div>
        <button type="button" className="btn-outline" onClick={exportCsv} disabled={exporting}>
          <Download size={14} aria-hidden="true" />
          {exporting ? 'Exporting…' : 'Export CSV'}
        </button>
      </header>

      <form className="panel grid grid-cols-2 gap-3 p-4 md:grid-cols-6" onSubmit={applyFilters}>
        <div className="col-span-2 md:col-span-1">
          <label className="label" htmlFor="filter-symbol">
            Symbol
          </label>
          <input
            id="filter-symbol"
            className="input uppercase"
            list="instrument-list"
            placeholder="e.g. RELIANCE"
            value={draft.symbol}
            onChange={(event) => setDraft((f) => ({ ...f, symbol: event.target.value }))}
          />
          <datalist id="instrument-list">
            {instruments.map((instrument) => (
              <option key={instrument.symbol} value={instrument.symbol}>
                {instrument.name}
              </option>
            ))}
          </datalist>
        </div>
        <div>
          <label className="label" htmlFor="filter-side">
            Side
          </label>
          <select
            id="filter-side"
            className="input"
            value={draft.side}
            onChange={(event) => setDraft((f) => ({ ...f, side: event.target.value }))}
          >
            <option value="">All</option>
            <option value="BUY">Buy</option>
            <option value="SELL">Sell</option>
          </select>
        </div>
        <div>
          <label className="label" htmlFor="filter-from">
            From
          </label>
          <input
            id="filter-from"
            type="date"
            className="input"
            value={draft.from}
            onChange={(event) => setDraft((f) => ({ ...f, from: event.target.value }))}
          />
        </div>
        <div>
          <label className="label" htmlFor="filter-to">
            To
          </label>
          <input
            id="filter-to"
            type="date"
            className="input"
            value={draft.to}
            onChange={(event) => setDraft((f) => ({ ...f, to: event.target.value }))}
          />
        </div>
        <div>
          <label className="label" htmlFor="filter-result">
            Result
          </label>
          <select
            id="filter-result"
            className="input"
            value={draft.result}
            onChange={(event) => setDraft((f) => ({ ...f, result: event.target.value }))}
          >
            <option value="">All</option>
            <option value="profit">Profitable</option>
            <option value="loss">Losing</option>
          </select>
        </div>
        <div className="col-span-2 flex items-end gap-2 md:col-span-1">
          <button type="submit" className="btn-accent flex-1">
            Apply
          </button>
          <button type="button" className="btn-ghost" onClick={clearFilters} title="Clear filters">
            <FilterX size={15} aria-hidden="true" />
            <span className="sr-only">Clear filters</span>
          </button>
        </div>
      </form>

      <section className="panel overflow-hidden">
        {loading && !data ? (
          <Spinner label="Loading trade history…" />
        ) : error && !data ? (
          <ErrorState message={error.message} onRetry={() => load(true)} />
        ) : data?.content?.length ? (
          <>
            <div className="overflow-x-auto">
              <table className="table-base">
                <thead>
                  <tr>
                    <th scope="col">Trade</th>
                    <th scope="col">Instrument</th>
                    <th scope="col" className="text-right">Qty</th>
                    <th scope="col" className="text-right">Price</th>
                    <th scope="col" className="text-right">Gross</th>
                    <th scope="col" className="text-right">Charges</th>
                    <th scope="col" className="text-right">Net</th>
                    <th scope="col" className="text-right">Realised P&L</th>
                    <th scope="col" className="text-right">Executed</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((trade) => {
                    const pnl = Number(trade.realizedPnl ?? 0);
                    return (
                      <tr key={trade.id}>
                        <td>
                          <p className="num text-xs text-slate-300">{trade.tradeRef}</p>
                          <p className="num text-[11px] text-ink-500">{trade.orderRef}</p>
                        </td>
                        <td>
                          <p className="font-medium text-slate-100">
                            <span className={trade.side === 'BUY' ? 'text-accent' : 'text-danger-soft'}>
                              {trade.side}
                            </span>{' '}
                            {trade.symbol}
                          </p>
                          <p className="text-xs text-ink-500">{trade.instrumentName}</p>
                        </td>
                        <td className="num text-right">{formatQuantity(trade.quantity)}</td>
                        <td className="num text-right">{formatMoney(trade.price, trade.currency)}</td>
                        <td className="num text-right">{formatMoney(trade.grossAmount, trade.accountCurrency)}</td>
                        <td className="num text-right">{formatMoney(trade.charges, trade.accountCurrency)}</td>
                        <td className="num text-right">{formatMoney(trade.netAmount, trade.accountCurrency)}</td>
                        <td className="num text-right">
                          {trade.side === 'SELL' ? (
                            <>
                              <span className={pnl > 0 ? 'text-accent' : pnl < 0 ? 'text-danger-soft' : ''}>
                                <span aria-hidden="true">{pnl > 0 ? '▲ ' : pnl < 0 ? '▼ ' : '• '}</span>
                                {formatMoney(trade.realizedPnl, trade.accountCurrency)}
                              </span>
                              <p className="text-xs text-ink-500">{formatPercent(trade.realizedPnlPercent)}</p>
                            </>
                          ) : (
                            <span className="text-ink-500">—</span>
                          )}
                        </td>
                        <td className="text-right text-xs text-ink-500">{formatDateTime(trade.executedAt)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              totalElements={data.totalElements}
              onChange={setPage}
            />
          </>
        ) : (
          <EmptyState
            title="No trades match these filters"
            description="Clear the filters or place an order to start building trade history."
          />
        )}
      </section>
    </div>
  );
}
