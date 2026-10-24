import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, RotateCcw } from 'lucide-react';
import { portfolioApi } from '../api/portfolio.js';
import { accountApi } from '../api/account.js';
import { apiError } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import { useMarket } from '../context/MarketContext.jsx';
import Spinner from '../components/ui/Spinner.jsx';
import ErrorState from '../components/ui/ErrorState.jsx';
import EmptyState from '../components/ui/EmptyState.jsx';
import ConfirmDialog from '../components/ui/ConfirmDialog.jsx';
import EquityCurve from '../components/portfolio/EquityCurve.jsx';
import DataSourceBadge from '../components/ui/DataSourceBadge.jsx';
import { formatDate, formatMoney, formatNumber, formatPercent, formatQuantity } from '../lib/format.js';
import CapitalPicker, { isValidVirtualCapital } from '../components/account/CapitalPicker.jsx';

export default function PortfolioPage() {
  const toast = useToast();
  const [portfolio, setPortfolio] = useState(null);
  const [performance, setPerformance] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [resetOpen, setResetOpen] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [resetCapital, setResetCapital] = useState('10000');
  const [resetCapitalError, setResetCapitalError] = useState('');
  const { quotes, subscribe, connected } = useMarket();

  const load = useCallback(async (showSpinner = false) => {
    if (showSpinner) setLoading(true);
    try {
      const [portfolioData, performanceData] = await Promise.all([
        portfolioApi.portfolio(),
        portfolioApi.performance().catch(() => null),
      ]);
      setPortfolio(portfolioData);
      setPerformance(performanceData);
      setError(null);
    } catch (failure) {
      setError(apiError(failure));
    } finally {
      if (showSpinner) setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(true);
    const interval = window.setInterval(() => load(false), 15000);
    return () => window.clearInterval(interval);
  }, [load]);

  useEffect(() => {
    if (!connected) return undefined;
    let cancel = () => {};
    accountApi
      .account()
      .then((account) => {
        cancel = subscribe(`/topic/account/${account.id}`, () => load(false));
      })
      .catch(() => {});
    return () => cancel();
  }, [connected, subscribe, load]);

  const holdings = useMemo(() => {
    if (!portfolio?.holdings) return [];
    const ledgerCurrency = portfolio.currency || 'INR';
    return portfolio.holdings.map((holding) => {
      const tick = quotes[holding.symbol];
      if (!tick?.lastPrice) return holding;
      // Ledger values arrive fully converted from the server. A live tick may only be folded into
      // them when the instrument is quoted in the ledger currency itself - mixing a native-price
      // value with a converted invested amount would fabricate a P&L. Non-ledger holdings keep the
      // server-marked value (still refreshed by the poll and account-event reloads) and only the
      // displayed last price follows the tick.
      if (holding.currency !== ledgerCurrency) {
        return { ...holding, currentPrice: tick.lastPrice };
      }
      const marketValue = Number(tick.lastPrice) * Number(holding.quantity);
      const invested = Number(holding.investedValue ?? 0);
      return {
        ...holding,
        currentPrice: tick.lastPrice,
        marketValue,
        unrealizedPnl: marketValue - invested,
        unrealizedPnlPercent: invested > 0 ? ((marketValue - invested) / invested) * 100 : 0,
      };
    });
  }, [portfolio, quotes]);

  const resetAccount = async () => {
    if (!isValidVirtualCapital(resetCapital)) {
      setResetCapitalError('Enter an amount from $100 to $1,000,000, with up to 2 decimal places.');
      return;
    }
    setResetting(true);
    try {
      const response = await accountApi.reset({ confirm: true, initialCapital: Number(resetCapital) });
      toast.success(response.message || 'Virtual account reset.', 'Account reset');
      setResetOpen(false);
      await load(false);
    } catch (failure) {
      toast.error(apiError(failure).message, 'Reset failed');
    } finally {
      setResetting(false);
    }
  };

  if (loading && !portfolio) {
    return <Spinner label="Valuing your portfolio…" />;
  }
  if (error && !portfolio) {
    return <ErrorState message={error.message} onRetry={() => load(true)} />;
  }

  const accountCurrency = portfolio?.currency || 'USD';

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-semibold text-slate-100">Portfolio</h1>
            <DataSourceBadge source={portfolio?.dataSource} label={portfolio?.dataSourceLabel} />
          </div>
          <p className="mt-0.5 text-sm text-ink-500">
            {portfolio?.accountNumber} · holdings marked at the latest quote
          </p>
        </div>
        <button type="button" className="btn-outline text-warning" onClick={() => {
          setResetCapital(String(portfolio?.currency === 'USD' ? portfolio.initialBalance : 10000));
          setResetCapitalError('');
          setResetOpen(true);
        }}>
          <RotateCcw size={14} aria-hidden="true" />
          Reset account
        </button>
      </header>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Metric label="Initial capital" value={formatMoney(portfolio?.initialBalance, accountCurrency)} />
        <Metric label="Equity / total account value" value={formatMoney(portfolio?.portfolioValue, accountCurrency)} />
        <Metric label="Available balance" value={formatMoney(portfolio?.availableCash, accountCurrency)} sub={`Reserved ${formatMoney(portfolio?.reservedCash, accountCurrency)}`} />
        <Metric label="Used margin" value={formatMoney(portfolio?.investedValue, accountCurrency)} sub="Open-position cost basis" />
        <Metric
          label="Unrealised P&L"
          value={formatMoney(portfolio?.unrealizedPnl, accountCurrency)}
          delta={Number(portfolio?.unrealizedPnl ?? 0)}
          sub={formatPercent(portfolio?.unrealizedPnlPercent)}
        />
        <Metric
          label="Total return"
          value={formatMoney(portfolio?.totalReturn, accountCurrency)}
          delta={Number(portfolio?.totalReturn ?? 0)}
          sub={formatPercent(portfolio?.totalReturnPercent)}
        />
      </div>

      {portfolio?.fxNote ? (
        <p className="rounded-lg border border-ink-700 bg-ink-900/50 px-3 py-2 text-[11px] text-ink-500">
          {portfolio.fxNote}
        </p>
      ) : null}

      <section className="panel overflow-hidden" aria-labelledby="holdings-heading">
        <div className="panel-header">
          <h2 id="holdings-heading" className="text-sm font-semibold text-slate-100">
            Holdings
          </h2>
          <span className="text-[11px] text-ink-500">{holdings.length} position(s)</span>
        </div>
        {holdings.length ? (
          <div className="overflow-x-auto">
            <table className="table-base">
              <thead>
                <tr>
                  <th scope="col">Instrument</th>
                  <th scope="col" className="text-right">Qty</th>
                  <th scope="col" className="text-right">Avg cost</th>
                  <th scope="col" className="text-right">Last</th>
                  <th scope="col" className="text-right">Market value</th>
                  <th scope="col" className="text-right">Unrealised P&L</th>
                  <th scope="col" className="text-right">Allocation</th>
                  <th scope="col">Risk plan</th>
                </tr>
              </thead>
              <tbody>
                {holdings.map((holding) => {
                  const pnl = Number(holding.unrealizedPnl ?? 0);
                  const direction = pnl > 0 ? 'up' : pnl < 0 ? 'down' : 'flat';
                  return (
                    <tr key={holding.symbol}>
                      <td>
                        <Link to={`/markets?symbol=${holding.symbol}`} className="font-medium text-slate-100 hover:text-accent">
                          {holding.symbol}
                        </Link>
                        <p className="text-xs text-ink-500">{holding.name}</p>
                      </td>
                      <td className="num text-right">{formatQuantity(holding.quantity)}</td>
                      <td className="num text-right">{formatMoney(holding.avgCost, holding.currency)}</td>
                      <td className="num text-right">
                        {holding.currentPrice != null ? formatMoney(holding.currentPrice, holding.currency) : '—'}
                      </td>
                      <td className="num text-right">{formatMoney(holding.marketValue, accountCurrency)}</td>
                      <td className="num text-right">
                        <span className={direction === 'up' ? 'text-accent' : direction === 'down' ? 'text-danger-soft' : ''}>
                          <span aria-hidden="true">{direction === 'up' ? '▲ ' : direction === 'down' ? '▼ ' : '• '}</span>
                          {formatMoney(holding.unrealizedPnl, accountCurrency)}
                        </span>
                        <p className="text-xs text-ink-500">{formatPercent(holding.unrealizedPnlPercent)}</p>
                      </td>
                      <td className="num text-right">
                        <div className="ml-auto flex w-28 items-center gap-2">
                          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-700">
                            <div
                              className="h-full rounded-full bg-accent"
                              style={{ width: `${Math.min(100, Number(holding.allocationPercent ?? 0))}%` }}
                            />
                          </div>
                          <span className="text-xs text-ink-500">{formatNumber(holding.allocationPercent, 1)}%</span>
                        </div>
                      </td>
                      <td className="text-xs text-ink-500">
                        {holding.stopLossPrice || holding.targetPrice ? (
                          <span className="num">
                            {holding.stopLossPrice ? `Stop ${formatMoney(holding.stopLossPrice, holding.currency)}` : 'No stop'}
                            {holding.targetPrice ? ` · Target ${formatMoney(holding.targetPrice, holding.currency)}` : ''}
                          </span>
                        ) : (
                          'None'
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="No open positions"
            description="Buy an instrument from the order ticket to start building your virtual portfolio."
            action={
              <Link to="/markets" className="btn-accent">
                Open charts
              </Link>
            }
          />
        )}
      </section>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <section className="panel xl:col-span-2" aria-labelledby="curve-heading">
          <div className="panel-header">
            <h2 id="curve-heading" className="text-sm font-semibold text-slate-100">
              Equity curve
            </h2>
            <span className="text-[11px] text-ink-500">
              {performance ? `${formatDate(performance.from)} → ${formatDate(performance.to)}` : ''}
            </span>
          </div>
          {performance?.equityCurve?.length ? (
            <EquityCurve points={performance.equityCurve} currency={performance.currency || accountCurrency} />
          ) : (
            <p className="px-4 py-10 text-center text-sm text-ink-500">
              Not enough history yet. The curve appears once trades are recorded.
            </p>
          )}
          {performance ? (
            <dl className="grid grid-cols-2 gap-x-6 gap-y-2 border-t border-ink-700 px-4 py-3 text-xs sm:grid-cols-4">
              <Stat label="Net change" value={formatMoney(performance.netChange, performance.currency || accountCurrency)} />
              <Stat label="Realised P&L" value={formatMoney(performance.realizedPnl, performance.currency || accountCurrency)} />
              <Stat label="Charges" value={formatMoney(performance.totalCharges, performance.currency || accountCurrency)} />
              <Stat label="Trades" value={formatNumber(performance.tradeCount, 0)} />
              <Stat label="Win rate" value={formatPercent(performance.winRate, { signed: false })} />
              <Stat label="Average win" value={formatMoney(performance.averageWin, performance.currency || accountCurrency)} />
              <Stat label="Average loss" value={formatMoney(performance.averageLoss, performance.currency || accountCurrency)} />
              <Stat label="Profit factor" value={formatNumber(performance.profitFactor, 2)} />
            </dl>
          ) : null}
        </section>

        <section className="panel" aria-labelledby="risk-note-heading">
          <div className="panel-header">
            <h2 id="risk-note-heading" className="text-sm font-semibold text-slate-100">
              Good to know
            </h2>
          </div>
          <ul className="space-y-3 p-4 text-xs text-ink-500">
            <li className="flex gap-2">
              <AlertTriangle size={14} className="mt-0.5 shrink-0 text-warning" aria-hidden="true" />
              Average cost includes the buy-side charges, so an immediate round-trip shows a small
              loss equal to the costs - exactly like a real broker account.
            </li>
            <li>
              Selling realises P&amp;L against the average cost; the identity{' '}
              <span className="num text-slate-300">portfolio value = grant + realised + unrealised</span>{' '}
              always holds.
            </li>
            <li>
              Resetting the account clears holdings, orders and trades, restores the selected initial
              capital and keeps the immutable ledger as an audit trail.
            </li>
          </ul>
        </section>
      </div>

      <ConfirmDialog
        open={resetOpen}
        title="Reset your virtual account?"
        busy={resetting}
        confirmLabel={`Reset to ${formatMoney(resetCapital, 'USD')}`}
        cancelLabel="Keep my progress"
        message={
          <div className="space-y-3">
            <p>
              This clears all holdings, orders and trade history and starts a new USD virtual session
              with the selected capital. The transaction ledger is kept for audit. This cannot be undone.
            </p>
            <CapitalPicker
              id="resetInitialCapital"
              value={resetCapital}
              onChange={(value) => {
                setResetCapital(value);
                setResetCapitalError('');
              }}
              error={resetCapitalError}
            />
          </div>
        }
        onConfirm={resetAccount}
        onCancel={() => (resetting ? undefined : setResetOpen(false))}
      />
    </div>
  );
}

function Metric({ label, value, sub, delta }) {
  const direction = delta === undefined ? null : delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat';
  return (
    <div className="panel p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500">{label}</p>
      <p className="num mt-1 text-lg font-semibold text-slate-100">{value}</p>
      {sub ? (
        <p
          className={`num mt-0.5 text-xs ${
            direction === 'up' ? 'text-accent' : direction === 'down' ? 'text-danger-soft' : 'text-ink-500'
          }`}
        >
          {direction ? (
            <span aria-hidden="true">{direction === 'up' ? '▲ ' : direction === 'down' ? '▼ ' : '• '}</span>
          ) : null}
          {sub}
        </p>
      ) : null}
    </div>
  );
}

function Stat({ label, value }) {
  return (
    <div>
      <dt className="text-ink-500">{label}</dt>
      <dd className="num font-medium text-slate-200">{value}</dd>
    </div>
  );
}
