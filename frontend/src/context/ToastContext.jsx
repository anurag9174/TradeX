import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { CheckCircle2, Info, XCircle, X } from 'lucide-react';

const ToastContext = createContext(null);

const ICONS = {
  success: CheckCircle2,
  error: XCircle,
  info: Info,
};

const STYLES = {
  success: 'border-accent/40 bg-ink-800 text-accent',
  error: 'border-danger/40 bg-ink-800 text-danger-soft',
  info: 'border-info/40 bg-ink-800 text-info',
};

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const counter = useRef(0);

  const dismiss = useCallback((id) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback(
    (type, message, title) => {
      const id = ++counter.current;
      setToasts((current) => [...current.slice(-4), { id, type, message, title }]);
      window.setTimeout(() => dismiss(id), type === 'error' ? 7000 : 4500);
      return id;
    },
    [dismiss],
  );

  const value = useMemo(
    () => ({
      toasts,
      dismiss,
      success: (message, title) => push('success', message, title),
      error: (message, title) => push('error', message, title),
      info: (message, title) => push('info', message, title),
    }),
    [toasts, dismiss, push],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        className="pointer-events-none fixed right-4 top-4 z-50 flex w-full max-w-sm flex-col gap-2"
        aria-live="polite"
        role="status"
      >
        {toasts.map((toast) => {
          const Icon = ICONS[toast.type] || Info;
          return (
            <div
              key={toast.id}
              className={`pointer-events-auto flex items-start gap-3 rounded-lg border px-4 py-3 shadow-panel animate-slide-in ${STYLES[toast.type] || STYLES.info}`}
            >
              <Icon size={18} className="mt-0.5 shrink-0" aria-hidden="true" />
              <div className="min-w-0 flex-1">
                {toast.title ? (
                  <p className="text-sm font-semibold text-slate-100">{toast.title}</p>
                ) : null}
                <p className="break-words text-sm text-slate-300">{toast.message}</p>
              </div>
              <button
                type="button"
                onClick={() => dismiss(toast.id)}
                className="rounded p-0.5 text-ink-500 hover:bg-ink-700 hover:text-slate-200"
                aria-label="Dismiss notification"
              >
                <X size={14} />
              </button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used inside ToastProvider');
  }
  return context;
}
