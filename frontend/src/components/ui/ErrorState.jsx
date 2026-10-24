import { AlertTriangle, RefreshCw } from 'lucide-react';

export default function ErrorState({ title = 'Something went wrong', message, onRetry }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-6 py-12 text-center" role="alert">
      <div className="rounded-full border border-danger/40 bg-danger/10 p-3 text-danger-soft">
        <AlertTriangle size={22} aria-hidden="true" />
      </div>
      <div>
        <p className="text-sm font-semibold text-slate-200">{title}</p>
        {message ? <p className="mt-1 max-w-md text-sm text-ink-500">{message}</p> : null}
      </div>
      {onRetry ? (
        <button type="button" className="btn-outline" onClick={onRetry}>
          <RefreshCw size={14} aria-hidden="true" />
          Try again
        </button>
      ) : null}
    </div>
  );
}
