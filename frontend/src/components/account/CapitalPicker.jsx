import { formatMoney } from '../../lib/format.js';

export const MIN_VIRTUAL_CAPITAL = 100;
export const MAX_VIRTUAL_CAPITAL = 1000000;
export const QUICK_CAPITAL_OPTIONS = [1000, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000];
const quickAmountFormatter = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

export function isValidVirtualCapital(value) {
  const amount = Number(value);
  const hasAtMostTwoDecimals = /^\d+(?:\.\d{1,2})?$/.test(String(value).trim());
  return Number.isFinite(amount)
    && amount >= MIN_VIRTUAL_CAPITAL
    && amount <= MAX_VIRTUAL_CAPITAL
    && hasAtMostTwoDecimals;
}

export default function CapitalPicker({ value, onChange, error, id = 'initialCapital' }) {
  return (
    <div className="space-y-3">
      <div>
        <label className="label" htmlFor={id}>Starting virtual capital (USD)</label>
        <div className="relative">
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-500">$</span>
          <input
            id={id}
            name={id}
            type="number"
            min={MIN_VIRTUAL_CAPITAL}
            max={MAX_VIRTUAL_CAPITAL}
            step="0.01"
            className={`input pl-8 ${error ? 'border-danger' : ''}`}
            value={value}
            onChange={(event) => onChange(event.target.value)}
            aria-invalid={Boolean(error)}
            aria-describedby={`${id}-limits${error ? ` ${id}-error` : ''}`}
            required
          />
        </div>
        <p id={`${id}-limits`} className="mt-1 text-xs text-ink-500">
          Minimum: $100 · Maximum: $1,000,000. This is simulated capital only.
        </p>
        {error ? <p id={`${id}-error`} role="alert" className="mt-1 text-xs text-danger-soft">{error}</p> : null}
      </div>
      <div className="flex flex-wrap gap-2" aria-label="Quick-select virtual capital">
        {QUICK_CAPITAL_OPTIONS.map((amount) => (
          <button
            key={amount}
            type="button"
            className={`btn-outline px-2.5 py-1.5 text-xs ${Number(value) === amount ? 'border-accent text-accent' : ''}`}
            aria-pressed={Number(value) === amount}
            onClick={() => onChange(String(amount))}
          >
            ${quickAmountFormatter.format(amount)}
          </button>
        ))}
      </div>
      <div className="rounded-lg border border-accent/30 bg-accent/5 px-3 py-2">
        <p className="text-[11px] uppercase tracking-wide text-ink-500">Selected starting capital</p>
        <p className="num mt-0.5 text-lg font-semibold text-accent">{formatMoney(value, 'USD')}</p>
      </div>
    </div>
  );
}
