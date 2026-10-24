import { Inbox } from 'lucide-react';

export default function EmptyState({ title, description, action = null, icon: Icon = Inbox }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-6 py-12 text-center">
      <div className="rounded-full border border-ink-700 bg-ink-800 p-3 text-ink-500">
        <Icon size={22} aria-hidden="true" />
      </div>
      <div>
        <p className="text-sm font-semibold text-slate-200">{title}</p>
        {description ? <p className="mt-1 max-w-md text-sm text-ink-500">{description}</p> : null}
      </div>
      {action}
    </div>
  );
}
