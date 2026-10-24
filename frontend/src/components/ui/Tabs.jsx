export default function Tabs({ tabs, active, onChange, ariaLabel }) {
  return (
    <div className="flex flex-wrap gap-1 border-b border-ink-700 px-2" role="tablist" aria-label={ariaLabel}>
      {tabs.map((tab) => {
        const selected = tab.value === active;
        return (
          <button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={`tab-panel-${tab.value}`}
            id={`tab-${tab.value}`}
            onClick={() => onChange(tab.value)}
            className={`relative -mb-px rounded-t-md px-3 py-2 text-sm font-medium transition-colors ${
              selected
                ? 'border-b-2 border-accent text-accent'
                : 'text-ink-500 hover:text-slate-200'
            }`}
          >
            {tab.label}
            {tab.count !== undefined && tab.count !== null ? (
              <span className="ml-1.5 rounded bg-ink-700/60 px-1.5 py-0.5 text-[11px] text-slate-300">
                {tab.count}
              </span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}
