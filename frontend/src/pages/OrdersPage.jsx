import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Ban, RefreshCw } from 'lucide-react';
import { ordersApi } from '../api/orders.js';
import { marketApi } from '../api/market.js';
import { apiError } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import { useMarket } from '../context/MarketContext.jsx';
import Tabs from '../components/ui/Tabs.jsx';
import Pagination from '../components/ui/Pagination.jsx';
import Spinner from '../components/ui/Spinner.jsx';
import ErrorState from '../components/ui/ErrorState.jsx';
import EmptyState from '../components/ui/EmptyState.jsx';
import ConfirmDialog from '../components/ui/ConfirmDialog.jsx';
import OrderPanel from '../components/orders/OrderPanel.jsx';
import { formatDateTime, formatMoney, formatQuantity } from '../lib/format.js';

const TABS = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Open' },
  { value: 'executed', label: 'Executed' },
  { value: 'cancelled', label: 'Cancelled' },
  { value: 'rejected', label: 'Rejected' },
];

const STATUS_STYLES = {
  EXECUTED: 'badge-live',
  PENDING: 'border border-warning/40 bg-warning/10 text-warning',
  PARTIALLY_FILLED: 'border border-warning/40 bg-warning/10 text-warning',
  REJECTED: 'border border-danger/40 bg-danger/10 text-danger-soft',
  CANCELLED: 'badge-neutral',
  EXPIRED: 'badge-neutral',
};

export default function OrdersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [tab, setTab] = useState('all');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [instruments, setInstruments] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [cancelTarget, setCancelTarget] = useState(null);
  const [cancelling, setCancelling] = useState(false);
  // Bumped whenever cash or reservations change (placement, cancellation) so the order ticket
  // re-runs its server-side estimate and stops showing a stale available-cash figure.
  const [panelRefresh, setPanelRefresh] = useState(0);
  const toast = useToast();
  const { quotes } = useMarket();

  const symbol = (searchParams.get('symbol') || 'RELIANCE').toUpperCase();
  const setSymbol = (next) => setSearchParams({ symbol: next }, { replace: true });

  const load = useCallback(
    async (showSpinner = false) => {
      if (showSpinner) setLoading(true);
      try {
        const result = await ordersApi.list({ tab, page, size: 15 });
        setData(result);
        setError(null);
      } catch (failure) {
        setError(apiError(failure));
      } finally {
        if (showSpinner) setLoading(false);
      }
    },
    [tab, page],
  );

  useEffect(() => {
    load(true);
  }, [load]);

  // Keep working orders fresh so pending limit/stop orders move on their own.
  useEffect(() => {
    const interval = window.setInterval(() => load(false), 15000);
    return () => window.clearInterval(interval);
  }, [load]);

  useEffect(() => {
    marketApi
      .instruments()
      .then(setInstruments)
      .catch(() => setInstruments([]));
  }, []);

  const requestCancel = async () => {
    if (!cancelTarget) return;
    setCancelling(true);
    try {
      await ordersApi.cancel(cancelTarget.id);
      toast.success(`Order ${cancelTarget.orderRef} cancelled.`);
      setCancelTarget(null);
      await load(false);
      setPanelRefresh((n) => n + 1);
    } catch (failure) {
      toast.error(apiError(failure).message, 'Could not cancel');
    } finally {
      setCancelling(false);
    }
  };

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
      <div className="space-y-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-100">Orders</h1>
            <p className="mt-0.5 text-sm text-ink-500">
              Every simulated order, with its working, executed, cancelled and rejected states.
            </p>
          </div>
          <button
            type="button"
            className="btn-outline"
            onClick={() => load(true)}
            title="Refresh orders"
          >
            <RefreshCw size={14} aria-hidden="true" />
            Refresh
          </button>
        </header>

        <section className="panel overflow-hidden">
          <Tabs
            ariaLabel="Order status"
            tabs={TABS}
            active={tab}
            onChange={(value) => {
              setTab(value);
              setPage(0);
            }}
          />

          {loading && !data ? (
            <Spinner label="Loading orders…" />
          ) : error && !data ? (
            <ErrorState message={error.message} onRetry={() => load(true)} />
          ) : data?.content?.length ? (
            <>
              <div className="overflow-x-auto">
                <table className="table-base">
                  <thead>
                    <tr>
                      <th scope="col">Order</th>
                      <th scope="col">Instrument</th>
                      <th scope="col" className="text-right">Qty / filled</th>
                      <th scope="col" className="text-right">Price</th>
                      <th scope="col" className="text-right">Value</th>
                      <th scope="col">Status</th>
                      <th scope="col" className="text-right">Placed</th>
                      <th scope="col" className="text-right">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((order) => (
                      <tr key={order.id}>
                        <td>
                          <p className="num text-xs text-slate-300">{order.orderRef}</p>
                          <p className="text-[11px] uppercase tracking-wide text-ink-500">
                            {order.orderType.replace('_', ' ')}
                          </p>
                        </td>
                        <td>
                          <p className="font-medium text-slate-100">
                            <span className={order.side === 'BUY' ? 'text-accent' : 'text-danger-soft'}>
                              {order.side}
                            </span>{' '}
                            {order.symbol}
                          </p>
                          <p className="text-xs text-ink-500">{order.instrumentName}</p>
                        </td>
                        <td className="num text-right">
                          {formatQuantity(order.quantity)}
                          <span className="text-ink-500"> / {formatQuantity(order.filledQuantity)}</span>
                        </td>
                        <td className="num text-right">
                          {order.avgFillPrice != null
                            ? formatMoney(order.avgFillPrice, order.currency)
                            : order.limitPrice != null
                              ? formatMoney(order.limitPrice, order.currency)
                              : order.stopPrice != null
                                ? formatMoney(order.stopPrice, order.currency)
                                : 'Market'}
                        </td>
                        <td className="num text-right">
                          {order.executedValue != null ? formatMoney(order.executedValue, order.accountCurrency) : '—'}
                        </td>
                        <td>
                          <span className={`badge ${STATUS_STYLES[order.status] || 'badge-neutral'}`}>
                            {order.statusLabel || order.status}
                          </span>
                          {order.rejectReason ? (
                            <p className="mt-1 max-w-[220px] text-[11px] text-danger-soft">{order.rejectReason}</p>
                          ) : null}
                        </td>
                        <td className="text-right text-xs text-ink-500">{formatDateTime(order.createdAt)}</td>
                        <td className="text-right">
                          {order.cancellable ? (
                            <button
                              type="button"
                              className="btn-ghost px-2 py-1 text-danger-soft"
                              onClick={() => setCancelTarget(order)}
                            >
                              <Ban size={14} aria-hidden="true" />
                              Cancel
                            </button>
                          ) : (
                            <span className="text-xs text-ink-500">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
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
              title="No orders in this tab"
              description="Place a simulated order from the ticket on the right; it will appear here with its full lifecycle."
            />
          )}
        </section>
      </div>

      <OrderPanel
        symbols={instruments}
        selectedSymbol={symbol}
        onSymbolChange={setSymbol}
        quote={quotes[symbol]}
        refreshToken={panelRefresh}
        onPlaced={() => {
          load(false);
          setPanelRefresh((n) => n + 1);
        }}
      />

      <ConfirmDialog
        open={Boolean(cancelTarget)}
        title="Cancel this order?"
        busy={cancelling}
        confirmLabel="Cancel order"
        cancelLabel="Keep order"
        message={
          cancelTarget ? (
            <span>
              {cancelTarget.side} {formatQuantity(cancelTarget.quantity)} {cancelTarget.symbol} ({cancelTarget.orderRef}).
              Any cash reserved by this order is released back to your available balance.
            </span>
          ) : null
        }
        onConfirm={requestCancel}
        onCancel={() => (cancelling ? undefined : setCancelTarget(null))}
      />
    </div>
  );
}
