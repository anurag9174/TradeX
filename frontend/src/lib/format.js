const CURRENCY_LOCALE = 'en-IN';

export function formatMoney(value, currency = 'INR', { signed = false, compact = false } = {}) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '—';
  }
  const amount = Number(value);
  const currencyCode = typeof currency === 'string' ? currency.toUpperCase() : 'INR';
  const formatter = new Intl.NumberFormat(currencyCode === 'USD' ? 'en-US' : CURRENCY_LOCALE, {
    style: 'currency',
    currency: currencyCode,
    minimumFractionDigits: compact ? 0 : 2,
    maximumFractionDigits: compact ? 0 : 2,
    signDisplay: signed ? 'exceptZero' : 'auto',
    notation: compact && Math.abs(amount) >= 100000 ? 'compact' : 'standard',
  });
  return formatter.format(amount);
}

export function formatNumber(value, fractionDigits = 2) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '—';
  }
  return new Intl.NumberFormat(CURRENCY_LOCALE, {
    minimumFractionDigits: 0,
    maximumFractionDigits: fractionDigits,
  }).format(Number(value));
}

export function formatQuantity(value) {
  if (value === null || value === undefined) return '—';
  const numeric = Number(value);
  return Number.isInteger(numeric) ? String(numeric) : formatNumber(numeric, 6);
}

export function formatPercent(value, { signed = true, fractionDigits = 2 } = {}) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '—';
  }
  const numeric = Number(value);
  const sign = signed && numeric > 0 ? '+' : '';
  // Percentages always show a fixed number of decimals so that a column of values lines up.
  const formatted = new Intl.NumberFormat(CURRENCY_LOCALE, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(numeric);
  return `${sign}${formatted}%`;
}

export function formatDateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

export function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(date);
}

export function formatRelative(value) {
  if (!value) return '—';
  const date = new Date(value);
  const diffSeconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (Math.abs(diffSeconds) < 5) return 'just now';
  if (diffSeconds < 60) return `${diffSeconds}s ago`;
  if (diffSeconds < 3600) return `${Math.round(diffSeconds / 60)}m ago`;
  if (diffSeconds < 86400) return `${Math.round(diffSeconds / 3600)}h ago`;
  return formatDateTime(value);
}

/**
 * Direction is never conveyed by colour alone: every price change is rendered with an explicit
 * sign and an arrow glyph, plus a screen-reader label.
 */
export function directionOf(value) {
  const numeric = Number(value ?? 0);
  if (numeric > 0) return 'up';
  if (numeric < 0) return 'down';
  return 'flat';
}

export const DIRECTION_TEXT = { up: 'gain', down: 'loss', flat: 'unchanged' };
export const DIRECTION_ARROW = { up: '▲', down: '▼', flat: '•' };
export const DIRECTION_CLASS = {
  up: 'text-accent',
  down: 'text-danger-soft',
  flat: 'text-ink-500',
};

export function initialsOf(name = '') {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('');
}

export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
