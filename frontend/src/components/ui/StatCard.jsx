import { DIRECTION_ARROW, DIRECTION_CLASS, directionOf } from '../../lib/format.js';

/**
 * Dashboard summary card. Trend direction is conveyed with an arrow + textual label,
 * never with colour alone.
 */
export default function StatCard({
  label,
  value,
  sub,
  delta,
  deltaLabel,
  icon: Icon,
  hint,
}) {
  const direction = delta === undefined || delta === null ? null : directionOf(delta);
  return (
    <div className="panel p-4">
      <div className="flex items-start justify-between gap-2">
        <p className="text-xs font-medium uppercase tracking-wide text-ink-500">{label}</p>
        {Icon ? (
          <span className="rounded-lg border border-ink-700 bg-ink-900 p-1.5 text-ink-500">
            <Icon size={15} aria-hidden="true" />
          </span>
        ) : null}
      </div>
      <p className="num mt-2 text-xl font-semibold text-slate-100">{value}</p>
      <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs">
        {direction ? (
          <span className={`num inline-flex items-center gap-1 ${DIRECTION_CLASS[direction]}`}>
            <span aria-hidden="true">{DIRECTION_ARROW[direction]}</span>
            <span className="sr-only">{direction === 'up' ? 'Gain of' : direction === 'down' ? 'Loss of' : ''}</span>
            {deltaLabel}
          </span>
        ) : null}
        {sub ? <span className="text-ink-500">{sub}</span> : null}
      </div>
      {hint ? <p className="mt-2 text-[11px] leading-snug text-ink-500">{hint}</p> : null}
    </div>
  );
}
