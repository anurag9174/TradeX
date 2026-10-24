import { useEffect, useRef } from 'react';
import { AlertTriangle } from 'lucide-react';

/**
 * Accessible confirmation dialog used for destructive or financial actions
 * (account reset, order submission, journal deletion).
 */
export default function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'danger',
  busy = false,
  onConfirm,
  onCancel,
}) {
  const confirmRef = useRef(null);

  useEffect(() => {
    if (open && confirmRef.current) {
      confirmRef.current.focus();
    }
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event) => {
      if (event.key === 'Escape' && !busy) {
        onCancel?.();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, busy, onCancel]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/60 p-4 animate-fade-in" role="presentation" onClick={busy ? undefined : onCancel}>
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        aria-describedby="confirm-message"
        className="panel w-full max-w-md p-5"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start gap-3">
          <div
            className={`rounded-full p-2 ${tone === 'danger' ? 'bg-danger/10 text-danger-soft' : 'bg-accent/10 text-accent'}`}
          >
            <AlertTriangle size={20} aria-hidden="true" />
          </div>
          <div className="min-w-0">
            <h2 id="confirm-title" className="text-base font-semibold text-slate-100">
              {title}
            </h2>
            <div id="confirm-message" className="mt-1 text-sm text-ink-500">
              {message}
            </div>
          </div>
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button type="button" className="btn-outline" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </button>
          <button
            ref={confirmRef}
            type="button"
            className={tone === 'danger' ? 'btn-danger' : 'btn-accent'}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
