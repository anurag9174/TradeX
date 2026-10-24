import { Radio } from 'lucide-react';

/**
 * The truthfulness badge. Every payload from the backend states its data source; this component
 * renders that verbatim. It can never show "LIVE" for simulated numbers because the label
 * comes from the server.
 */
export default function DataSourceBadge({ source, label, className = '' }) {
  const isLive = String(source || '').toUpperCase() === 'LIVE';
  return (
    <span
      className={`${isLive ? 'badge-live' : 'badge-sim'} ${className}`}
      title={label || (isLive ? 'Live market data' : 'Generated prices - not real market data')}
    >
      <Radio size={11} aria-hidden="true" />
      {isLive ? 'LIVE' : 'SIMULATED'}
    </span>
  );
}
