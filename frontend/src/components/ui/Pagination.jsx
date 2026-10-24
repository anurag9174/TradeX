import { ChevronLeft, ChevronRight } from 'lucide-react';

export default function Pagination({ page = 0, totalPages = 1, totalElements, onChange }) {
  if (!totalPages || totalPages <= 1) {
    return (
      <div className="flex items-center justify-between px-4 py-3 text-xs text-ink-500">
        <span>{totalElements ?? 0} record(s)</span>
      </div>
    );
  }
  return (
    <div className="flex items-center justify-between px-4 py-3 text-xs text-ink-500">
      <span>
        Page {page + 1} of {totalPages} · {totalElements ?? 0} record(s)
      </span>
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="btn-ghost px-2 py-1"
          disabled={page <= 0}
          onClick={() => onChange(page - 1)}
          aria-label="Previous page"
        >
          <ChevronLeft size={16} />
        </button>
        <button
          type="button"
          className="btn-ghost px-2 py-1"
          disabled={page >= totalPages - 1}
          onClick={() => onChange(page + 1)}
          aria-label="Next page"
        >
          <ChevronRight size={16} />
        </button>
      </div>
    </div>
  );
}
