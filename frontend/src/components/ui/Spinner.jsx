import { Loader2 } from 'lucide-react';

export default function Spinner({ label = 'Loading…', className = '' }) {
  return (
    <div className={`flex items-center justify-center gap-2 py-8 text-sm text-ink-500 ${className}`}>
      <Loader2 className="animate-spin" size={18} aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}
