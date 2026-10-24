import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity,
  Banknote,
  Briefcase,
  Landmark,
  Percent,
  PiggyBank,
  TrendingDown,
  TrendingUp,
} from 'lucide-react';
import { accountApi } from '../api/account.js';
import { ordersApi } from '../api/orders.js';
import { watchlistApi } from '../api/watchlist.js';
import { apiError } from '../api/client.js';
import { useMarket } from '../context/MarketContext.jsx';
import StatCard from '../components/ui/StatCard.jsx';
import Spinner from '../components/ui/Spinner.jsx';
import ErrorState from '../components/ui/ErrorState.jsx';
import QuoteTable from '../components/market/QuoteTable.jsx';
import { formatDateTime, formatMoney, formatPercent, formatQuantity } from '../lib/format.js';

const OVERVIEW_REFRESH_MS = 15000;

export default function DashboardPage() {
  const [summary, setSummary] = useState(null);
  const [watchlist, setWatchlist] = useState(null);
  const [recentOrders, setRecentOrders] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const { quotes, subscribe, connected } = useMarket();

  const load = useCallback(async () => {
    try {
      const [summaryData, watchlistData, ordersData] = await Promise.all([
        accountApi.summary(),
        watchlistApi.list(),
        ordersApi.list({ size: 5 }),
      ]);
      setSummary(summaryData);
      setWatchlist(watchlistData);
      setRecentOrders(ordersData);
      setError(null);
    } catch (failure) {
      setError(apiError(failure));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const interval = window.setInterval(load, OVERVIEW_REFRESH_MS);
    return () => window.clearInterval(interval);
  }, [load]);

  // The backend pushes an authoritative account snapshot after every trade, reset or order change.
  useEffect(() => {
    if (!connected) return undefined;
    let cancel = () => {};
    accountApi
      .account()
      .then((account) => {
        cancel = subscribe(`/topic/account/${account.id}`, () => load());
      })
      .catch(() => {});
    return () => cancel();
  }, [connected, subscribe, load]);

  const mergedWatchlist = useMemo(() => {
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

  if (loading) {
    return <Spinner label="Loading your dashboard…" />;
  }

  if (error && !summary) {
    return <ErrorState message={error.message} onRetry={load} />;
  }

  const totalReturn = Number(summary?.totalReturn ?? 0);
  const todaysPnl = Number(summary?.todaysPnl ?? 0);
  const accountCurrency = summary?.currency || 'USD';

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-100">Dashboard</h1>
          <p className="mt-0.5 text-sm text-ink-500">
            Virtual account overview. Every figure is simulated unless the badge says LIVE.
          </p>
        </div>
        <div className="flex gap-2">
          <Link to="/markets" className="btn-accent">
            Open charts
          </Link>
          <Link to="/orders" className="btn-outline">
            Place order
          </Link>
        </div>
      </header>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Initial virtual capital"
          value={formatMoney(summary?.initialCapital, accountCurrency)}
          sub="Opening balance for this account"
          icon={Landmark}
        />
        <StatCard
          label="Available balance"
          value={formatMoney(summary?.availableCash, accountCurrency)}
          sub={`${accountCurrency} · spendable now${Number(summary?.reservedCash ?? 0) > 0 ? ` · ${formatMoney(summary.reservedCash, accountCurrency)} reserved` : ''}`}
          icon={Banknote}
          hint={summary?.openOrders ? `${summary.openOrders} working order(s) may hold reserved cash.` : 'No cash reserved by working orders.'}
        />
        <StatCard
          label="Equity / total account value"
          value={formatMoney(summary?.portfolioValue, accountCurrency)}
          delta={totalReturn}
          deltaLabel={formatMoney(Math.abs(totalReturn), accountCurrency)}
          sub={formatPercent(summary?.totalReturnPercent)}
          icon={Briefcase}
          hint="Cash plus holdings, marked at the latest quote."
        />
        <StatCard
          label="Used margin"
          value={formatMoney(summary?.investedValue, accountCurrency)}
          sub="Cost basis of open positions"
          icon={Briefcase}
          hint="Shown as invested cost basis; equity is valued using marked holdings, not by adding this cost basis again."
        />
        <StatCard
          label="Holdings value"
          value={formatMoney(summary?.holdingsValue, accountCurrency)}
          sub={`${summary?.openPositions ?? 0} open position(s)`}
          icon={PiggyBank}
        />
        <StatCard
          label="Unrealised P&L"
          value={formatMoney(summary?.unrealizedPnl, accountCurrency)}
          delta={Number(summary?.unrealizedPnl ?? 0)}
          deltaLabel={formatMoney(Math.abs(Number(summary?.unrealizedPnl ?? 0)), accountCurrency)}
          sub="Open positions vs their average cost"
          icon={Number(summary?.unrealizedPnl ?? 0) >= 0 ? TrendingUp : TrendingDown}
        />
        <StatCard
          label="Realised P&L"
          value={formatMoney(summary?.realizedPnl, accountCurrency)}
          delta={Number(summary?.realizedPnl ?? 0)}
          deltaLabel={formatMoney(Math.abs(Number(summary?.realizedPnl ?? 0)), accountCurrency)}
          sub="Locked in by completed sells"
          icon={Activity}
        />
        <StatCard
          label="Today's P&L"
          value={formatMoney(summary?.todaysPnl, accountCurrency)}
          delta={todaysPnl}
          deltaLabel={formatMoney(Math.abs(todaysPnl), accountCurrency)}
          sub={`${summary?.dayTradeCount ?? 0} trade(s) today`}
          icon={todaysPnl >= 0 ? TrendingUp : TrendingDown}
          hint={`Charges booked today: ${formatMoney(summary?.todaysCharges, accountCurrency)}`}
        />
        <StatCard
          label="Total return"
          value={formatPercent(summary?.totalReturnPercent)}
          delta={totalReturn}
          deltaLabel={formatMoney(Math.abs(totalReturn), accountCurrency)}
          sub="Versus your initial virtual capital"
          icon={Percent}
        />
      </div>

      {summary?.fxNote ? (
        <p className="rounded-lg border border-ink-700 bg-ink-900/50 px-3 py-2 text-[11px] text-ink-500">
          {summary.fxNote}
        </p>
      ) : null}

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <section className="panel xl:col-span-2" aria-labelledby="watchlist-heading">
          <div className="panel-header">
            <h2 id="watchlist-heading" className="text-sm font-semibold text-slate-100">
              Watchlist
            </h2>
            <Link to="/watchlist" className="link-accent text-xs">
              Manage
            </Link>
          </div>
          <QuoteTable
            items={mergedWatchlist.slice(0, 8)}
            emptyMessage="Your watchlist is empty. Add instruments from the Watchlist page."
          />
        </section>

        <section className="panel" aria-labelledby="recent-orders-heading">
          <div className="panel-header">
            <h2 id="recent-orders-heading" className="text-sm font-semibold text-slate-100">
              Recent orders
            </h2>
            <Link to="/orders" className="link-accent text-xs">
              View all
            </Link>
          </div>
          {recentOrders?.content?.length ? (
            <ul className="divide-y divide-ink-800">
              {recentOrders.content.map((order) => (
                <li key={order.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium text-slate-100">
                      <span className={order.side === 'BUY' ? 'text-accent' : 'text-danger-soft'}>
                        {order.side}
                      </span>{' '}
                      {formatQuantity(order.quantity)} {order.symbol}
                    </p>
                    <p className="text-xs text-ink-500">
                      {order.orderType.replace('_', ' ')} · {formatDateTime(order.createdAt)}
                    </p>
                  </div>
                  <span
                    className={`badge ${
                      order.status === 'EXECUTED'
                        ? 'badge-live'
                        : order.status === 'REJECTED'
                          ? 'border border-danger/40 bg-danger/10 text-danger-soft'
                          : order.status === 'PENDING'
                            ? 'border border-warning/40 bg-warning/10 text-warning'
                            : 'badge-neutral'
                    }`}
                  >
                    {order.statusLabel || order.status}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="px-4 py-6 text-center text-sm text-ink-500">
              No orders yet. Place your first virtual trade from the Orders page.
            </p>
          )}
        </section>
      </div>

      <section className="panel p-4">
        <div className="flex items-center gap-2 text-xs text-ink-500">
          <Landmark size={14} aria-hidden="true" />
          <span>
            TradeX never connects to a brokerage. Every order here is executed against your own
            virtual account only.
          </span>
        </div>
      </section>
    </div>
  );
}
