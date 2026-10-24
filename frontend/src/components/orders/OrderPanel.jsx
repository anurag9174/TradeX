import { useEffect, useMemo, useRef, useState } from 'react';
import { ArrowDownRight, ArrowUpRight, Info, ShieldCheck } from 'lucide-react';
import { ordersApi } from '../../api/orders.js';
import { apiError } from '../../api/client.js';
import { useToast } from '../../context/ToastContext.jsx';
import ConfirmDialog from '../ui/ConfirmDialog.jsx';
import { formatMoney, formatNumber, formatPercent } from '../../lib/format.js';

const ORDER_TYPES = [
  { value: 'MARKET', label: 'Market', hint: 'Fills immediately at the current market price.' },
  { value: 'LIMIT', label: 'Limit', hint: 'Rests until the market reaches your price.' },
  { value: 'STOP_LOSS', label: 'Stop-loss', hint: 'Triggers a market order once the stop price trades.' },
];

function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return `tx-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Order-entry panel.
 *
 * The client never sends price or balance values that feed a calculation - it only names intent,
 * and the server answers with the authoritative estimate and execution result. A Confirm dialog
 * gates submission, and every request carries an idempotency key so a double click cannot become
 * a double trade.
 */
export default function OrderPanel({
  symbols = [],
  selectedSymbol,
  onSymbolChange,
  quote,
  onPlaced,
  lockedSymbol = false,
  refreshToken = 0,
}) {
  const toast = useToast();
  const [side, setSide] = useState('BUY');
  const [orderType, setOrderType] = useState('MARKET');
  const [quantity, setQuantity] = useState('1');
  const [limitPrice, setLimitPrice] = useState('');
  const [stopPrice, setStopPrice] = useState('');
  const [positionStopLoss, setPositionStopLoss] = useState('');
  const [positionTarget, setPositionTarget] = useState('');
  const [estimate, setEstimate] = useState(null);
  const [estimateError, setEstimateError] = useState(null);
  const [estimating, setEstimating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const idempotencyRef = useRef(newIdempotencyKey());

  const symbol = selectedSymbol;
  // Prices are keyed to the instrument's own quote currency (USD for Delta perpetuals); the
  // labels say so whenever a conversion is in play.
  const priceCurrency = quote?.currency || estimate?.priceCurrency || null;
  const priceSuffix = priceCurrency && priceCurrency !== 'INR' ? ` (${priceCurrency})` : '';

  const validation = useMemo(() => {
    const qty = Number(quantity);
    if (!symbol) return 'Choose an instrument first.';
    if (!quantity || Number.isNaN(qty) || qty <= 0) return 'Quantity must be greater than zero.';
    if (orderType === 'LIMIT' && (!limitPrice || Number(limitPrice) <= 0)) {
      return 'Limit orders need a limit price.';
    }
    if (orderType === 'STOP_LOSS' && (!stopPrice || Number(stopPrice) <= 0)) {
      return 'Stop-loss orders need a trigger price.';
    }
    if (positionStopLoss && Number(positionStopLoss) <= 0) return 'Stop-loss plan must be above zero.';
    if (positionTarget && Number(positionTarget) <= 0) return 'Target must be above zero.';
    return null;
  }, [symbol, quantity, orderType, limitPrice, stopPrice, positionStopLoss, positionTarget]);

  // Debounced, server-side estimate. The estimate is advisory only; execution re-derives it.
  useEffect(() => {
    if (validation) {
      setEstimate(null);
      setEstimateError(null);
      return undefined;
    }
    const handle = window.setTimeout(() => {
      setEstimating(true);
      ordersApi
        .estimate({
          symbol,
          side,
          orderType,
          quantity: Number(quantity),
          limitPrice: orderType === 'LIMIT' ? Number(limitPrice) : null,
          stopPrice: orderType === 'STOP_LOSS' ? Number(stopPrice) : null,
        })
        .then((value) => {
          setEstimate(value);
          setEstimateError(null);
        })
        .catch((failure) => {
          setEstimate(null);
          setEstimateError(apiError(failure).message);
        })
        .finally(() => setEstimating(false));
    }, 350);
    return () => window.clearTimeout(handle);
  }, [symbol, side, orderType, quantity, limitPrice, stopPrice, validation, refreshToken]);

  const resetPrices = (nextType) => {
    setOrderType(nextType);
    setEstimate(null);
  };

  const requestSubmit = (event) => {
    event.preventDefault();
    setError(null);
    if (validation) {
      setError(validation);
      return;
    }
    setConfirmOpen(true);
  };

  const confirmSubmit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const response = await ordersApi.place(
        {
          symbol,
          side,
          orderType,
          quantity: Number(quantity),
          limitPrice: orderType === 'LIMIT' ? Number(limitPrice) : null,
          stopPrice: orderType === 'STOP_LOSS' ? Number(stopPrice) : null,
          positionStopLoss: positionStopLoss ? Number(positionStopLoss) : null,
          positionTarget: positionTarget ? Number(positionTarget) : null,
        },
        idempotencyRef.current,
      );
      const status = response?.order?.status;
      if (status === 'REJECTED') {
        toast.error(response?.message || response?.order?.rejectReason || 'The order was rejected.', 'Order rejected');
      } else {
        toast.success(response?.message || `Order ${response?.order?.orderRef || ''} accepted.`, 'Order placed');
      }
      idempotencyRef.current = newIdempotencyKey();
      setConfirmOpen(false);
      setQuantity('1');
      setPositionStopLoss('');
      setPositionTarget('');
      setEstimate(null);
      onPlaced?.(response);
    } catch (failure) {
      const parsed = apiError(failure);
      setError(parsed.message);
      toast.error(parsed.message, 'Order failed');
      setConfirmOpen(false);
    } finally {
      setSubmitting(false);
    }
  };

  const est = estimate;
  const insufficient = est && ((side === 'BUY' && !est.sufficientFunds) || (side === 'SELL' && !est.sufficientHoldings));

  return (
    <section className="panel flex h-full flex-col" aria-labelledby="order-panel-heading">
      <div className="panel-header">
        <h2 id="order-panel-heading" className="text-sm font-semibold text-slate-100">
          Order ticket
        </h2>
        <span className="text-[11px] text-ink-500">Virtual orders only</span>
      </div>

      <form className="flex-1 space-y-4 p-4" onSubmit={requestSubmit}>
        <div>
          <label className="label" htmlFor="order-symbol">
            Instrument
          </label>
          <select
            id="order-symbol"
            className="input"
            value={symbol || ''}
            disabled={lockedSymbol}
            onChange={(event) => onSymbolChange?.(event.target.value)}
          >
            <option value="" disabled>
              Select an instrument
            </option>
            {symbols.map((item) => (
              <option key={item.symbol} value={item.symbol} disabled={item.tradable === false}>
                {item.symbol} - {item.name}
                {item.tradable === false ? ' (not tradable)' : ''}
              </option>
            ))}
          </select>
          {quote?.lastPrice != null ? (
            <p className="num mt-1 text-xs text-ink-500">
              Last {formatMoney(quote.lastPrice, quote.currency)}
              {quote.changePercent != null ? (
                <span className={Number(quote.changePercent) >= 0 ? 'ml-2 text-accent' : 'ml-2 text-danger-soft'}>
                  {Number(quote.changePercent) >= 0 ? '▲' : '▼'} {formatPercent(Math.abs(Number(quote.changePercent)))}
                </span>
              ) : null}
            </p>
          ) : null}
        </div>

        <fieldset>
          <legend className="label">Side</legend>
          <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="Order side">
            {[
              { value: 'BUY', label: 'Buy', icon: ArrowUpRight, cls: 'border-accent bg-accent/15 text-accent' },
              { value: 'SELL', label: 'Sell', icon: ArrowDownRight, cls: 'border-danger bg-danger/15 text-danger-soft' },
            ].map((option) => (
              <label
                key={option.value}
                className={`flex cursor-pointer items-center justify-center gap-1.5 rounded-lg border px-3 py-2 text-sm font-semibold transition-colors ${
                  side === option.value ? option.cls : 'border-ink-600 text-ink-500 hover:text-slate-200'
                }`}
              >
                <input
                  type="radio"
                  name="side"
                  value={option.value}
                  className="sr-only"
                  checked={side === option.value}
                  onChange={() => setSide(option.value)}
                />
                <option.icon size={15} aria-hidden="true" />
                {option.label}
              </label>
            ))}
          </div>
        </fieldset>

        <div>
          <label className="label" htmlFor="order-type">
            Order type
          </label>
          <select
            id="order-type"
            className="input"
            value={orderType}
            onChange={(event) => resetPrices(event.target.value)}
          >
            {ORDER_TYPES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <p className="mt-1 text-[11px] text-ink-500">{ORDER_TYPES.find((t) => t.value === orderType)?.hint}</p>
        </div>

        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="label" htmlFor="order-quantity">
              Quantity
            </label>
            <input
              id="order-quantity"
              className="input num"
              type="number"
              min="0"
              step="any"
              inputMode="decimal"
              value={quantity}
              onChange={(event) => setQuantity(event.target.value)}
              aria-invalid={Boolean(validation && quantity)}
            />
          </div>
          {orderType === 'LIMIT' ? (
            <div>
              <label className="label" htmlFor="order-limit">
                Limit price{priceSuffix}
              </label>
              <input
                id="order-limit"
                className="input num"
                type="number"
                min="0"
                step="0.05"
                inputMode="decimal"
                value={limitPrice}
                onChange={(event) => setLimitPrice(event.target.value)}
              />
            </div>
          ) : null}
          {orderType === 'STOP_LOSS' ? (
            <div>
              <label className="label" htmlFor="order-stop">
                Trigger price{priceSuffix}
              </label>
              <input
                id="order-stop"
                className="input num"
                type="number"
                min="0"
                step="0.05"
                inputMode="decimal"
                value={stopPrice}
                onChange={(event) => setStopPrice(event.target.value)}
              />
            </div>
          ) : null}
        </div>

        <details className="rounded-lg border border-ink-700 bg-ink-900/50 px-3 py-2">
          <summary className="cursor-pointer text-xs font-medium text-slate-300">
            <ShieldCheck size={13} className="mr-1 inline" aria-hidden="true" />
            Optional risk plan (attached stop-loss / target)
          </summary>
          <div className="mt-2 grid grid-cols-2 gap-2">
            <div>
              <label className="label" htmlFor="order-plan-stop">
                Stop-loss{priceSuffix}
              </label>
              <input
                id="order-plan-stop"
                className="input num"
                type="number"
                min="0"
                step="0.05"
                value={positionStopLoss}
                onChange={(event) => setPositionStopLoss(event.target.value)}
              />
            </div>
            <div>
              <label className="label" htmlFor="order-plan-target">
                Target{priceSuffix}
              </label>
              <input
                id="order-plan-target"
                className="input num"
                type="number"
                min="0"
                step="0.05"
                value={positionTarget}
                onChange={(event) => setPositionTarget(event.target.value)}
              />
            </div>
          </div>
          <p className="mt-1 text-[11px] text-ink-500">
            The matching engine watches these levels and submits a protective exit when they trigger.
          </p>
        </details>

        <div className="rounded-lg border border-ink-700 bg-ink-900/60 p-3 text-xs" aria-live="polite">
          <p className="mb-2 flex items-center gap-1.5 font-medium text-slate-300">
            <Info size={13} aria-hidden="true" />
            Estimate {estimating ? '(refreshing…)' : ''}
          </p>
          {est ? (
            <dl className="num space-y-1 text-ink-500">
              <Row label="Assumed price" value={formatMoney(est.assumedExecutionPrice, est.priceCurrency)} />
              <Row label="Order value" value={formatMoney(est.orderValue, est.ledgerCurrency)} />
              <Row label="Estimated charges" value={formatMoney(est.charges?.total, est.ledgerCurrency)} />
              <Row
                label={side === 'BUY' ? 'Total cost' : 'Net proceeds'}
                value={formatMoney(side === 'BUY' ? est.totalCost : est.netProceeds, est.ledgerCurrency)}
                strong
              />
              <Row label="Available balance" value={formatMoney(est.availableCash, est.ledgerCurrency)} />
              {side === 'SELL' ? <Row label="Held quantity" value={formatNumber(est.heldQuantity, 6)} /> : null}
              {insufficient ? (
                <p className="mt-1 font-medium text-danger-soft">
                  {side === 'BUY'
                    ? `Short by ${formatMoney(est.shortfallBy, est.ledgerCurrency)}.`
                    : 'You do not hold enough of this instrument.'}
                </p>
              ) : null}
              {est.warnings?.length ? (
                <ul className="mt-1 list-disc pl-4 text-warning">
                  {est.warnings.map((warning) => (
                    <li key={warning}>{warning}</li>
                  ))}
                </ul>
              ) : null}
              {est.fxRate != null && Number(est.fxRate) !== 1 ? (
                <p className="mt-1 text-[11px] text-ink-500">
                  {est.priceCurrency} order: prices are in {est.priceCurrency}; cash, charges and P&amp;L stay in{' '}
                  {est.ledgerCurrency}, converted at the nominal rate 1 {est.priceCurrency} ={' '}
                  {formatNumber(est.fxRate, 4)} {est.ledgerCurrency}.
                </p>
              ) : null}
            </dl>
          ) : estimateError ? (
            <p className="text-danger-soft">{estimateError}</p>
          ) : (
            <p className="text-ink-500">
              {validation || 'Enter a quantity to see the server-computed value, charges and affordability.'}
            </p>
          )}
        </div>

        {error ? (
          <p className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-xs text-danger-soft" role="alert">
            {error}
          </p>
        ) : null}

        <button
          type="submit"
          className={side === 'BUY' ? 'btn-buy w-full' : 'btn-sell w-full'}
          disabled={submitting || Boolean(validation)}
        >
          {submitting ? 'Placing…' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${symbol || ''}`}
        </button>
      </form>

      <ConfirmDialog
        open={confirmOpen}
        title={`Confirm ${side === 'BUY' ? 'buy' : 'sell'} order`}
        tone={side === 'BUY' ? 'accent' : 'danger'}
        busy={submitting}
        confirmLabel={side === 'BUY' ? 'Place buy order' : 'Place sell order'}
        message={
          <div className="space-y-1">
            <p>
              {side} <strong>{quantity}</strong> {symbol} as a <strong>{orderType.replace('_', ' ')}</strong> order.
            </p>
            {est ? (
              <p className="num text-slate-300">
                Estimated {side === 'BUY' ? 'cost' : 'proceeds'}:{' '}
                {formatMoney(side === 'BUY' ? est.totalCost : est.netProceeds, est.ledgerCurrency)} (includes{' '}
                {formatMoney(est.charges?.total, est.ledgerCurrency)} charges).
              </p>
            ) : null}
            <p className="text-ink-500">This is a simulated order - no real market order is placed.</p>
          </div>
        }
        onConfirm={confirmSubmit}
        onCancel={() => (submitting ? undefined : setConfirmOpen(false))}
      />
    </section>
  );
}

function Row({ label, value, strong = false }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <dt>{label}</dt>
      <dd className={strong ? 'font-semibold text-slate-100' : 'text-slate-300'}>{value}</dd>
    </div>
  );
}
