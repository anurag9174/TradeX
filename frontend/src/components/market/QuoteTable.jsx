import { Link, useNavigate } from 'react-router-dom';
import { LineChart, Star } from 'lucide-react';
import PriceDelta from '../ui/PriceDelta.jsx';
import DataSourceBadge from '../ui/DataSourceBadge.jsx';
import { formatMoney, formatNumber } from '../../lib/format.js';

/**
 * Quote table with prices that tick over the shared WebSocket. Rows whose instrument has no
 * live price (e.g. a live provider that does not cover an index) say so instead of showing
 * a fabricated number.
 */
export default function QuoteTable({ items, onRemove, emptyMessage }) {
  const navigate = useNavigate();

  if (!items?.length) {
    return <p className="px-4 py-6 text-center text-sm text-ink-500">{emptyMessage || 'No instruments to show.'}</p>;
  }

  return (
    <div className="overflow-x-auto">
      <table className="table-base">
        <thead>
          <tr>
            <th scope="col">Instrument</th>
            <th scope="col" className="text-right">Last price</th>
            <th scope="col" className="text-right">Change</th>
            <th scope="col" className="text-right">Day range</th>
            <th scope="col" className="text-right">Source</th>
            <th scope="col" className="text-right">Actions</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const quote = item.quote || item;
            const symbol = item.symbol || quote.symbol;
            const available = quote.priceAvailable !== false && quote.available !== false && quote.lastPrice != null;
            return (
              <tr key={`${symbol}-${item.instrumentId ?? symbol}`}>
                <td>
                  <div className="flex items-center gap-2">
                    <div>
                      <p className="font-medium text-slate-100">{symbol}</p>
                      <p className="text-xs text-ink-500">{item.name || quote.name}</p>
                    </div>
                    {item.assetClass === 'INDEX' ? <span className="badge-neutral">Index</span> : null}
                  </div>
                </td>
                <td className="num text-right">
                  {available ? formatMoney(quote.lastPrice, quote.currency) : <span className="text-ink-500">Unavailable</span>}
                </td>
                <td className="text-right">
                  {available ? (
                    <PriceDelta change={quote.change} changePercent={quote.changePercent} />
                  ) : (
                    <span className="text-xs text-ink-500" title={quote.unavailableReason || 'No price from the active provider'}>
                      Not covered
                    </span>
                  )}
                </td>
                <td className="num text-right text-xs text-ink-500">
                  {available && quote.dayLow != null && quote.dayHigh != null
                    ? `${formatNumber(quote.dayLow)} – ${formatNumber(quote.dayHigh)}`
                    : '—'}
                </td>
                <td className="text-right">
                  <DataSourceBadge source={quote.dataSource} label={quote.dataSourceLabel} />
                </td>
                <td>
                  <div className="flex justify-end gap-1">
                    <button
                      type="button"
                      className="btn-ghost px-2 py-1"
                      title={`Open ${symbol} chart`}
                      onClick={() => navigate(`/markets?symbol=${encodeURIComponent(symbol)}`)}
                    >
                      <LineChart size={15} aria-hidden="true" />
                      <span className="sr-only">Open chart for {symbol}</span>
                    </button>
                    {onRemove ? (
                      <button
                        type="button"
                        className="btn-ghost px-2 py-1 text-warning"
                        title={`Remove ${symbol} from watchlist`}
                        onClick={() => onRemove(symbol)}
                      >
                        <Star size={15} className="fill-current" aria-hidden="true" />
                        <span className="sr-only">Remove {symbol} from watchlist</span>
                      </button>
                    ) : (
                      <Link
                        className="btn-ghost px-2 py-1"
                        to={`/orders?symbol=${encodeURIComponent(symbol)}`}
                        title={`Trade ${symbol}`}
                      >
                        Trade
                      </Link>
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
