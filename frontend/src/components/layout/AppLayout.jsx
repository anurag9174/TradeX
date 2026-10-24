import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
  BarChart3,
  Briefcase,
  CandlestickChart,
  ClipboardList,
  History,
  LayoutDashboard,
  LogOut,
  ShieldAlert,
  Star,
  Wifi,
  WifiOff,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext.jsx';
import { useMarket } from '../../context/MarketContext.jsx';
import DataSourceBadge from '../ui/DataSourceBadge.jsx';
import { initialsOf } from '../../lib/format.js';

const NAV_ITEMS = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/markets', label: 'Markets', icon: CandlestickChart },
  { to: '/watchlist', label: 'Watchlist', icon: Star },
  { to: '/portfolio', label: 'Portfolio', icon: Briefcase },
  { to: '/orders', label: 'Orders', icon: ClipboardList },
  { to: '/trades', label: 'Trade history', icon: History },
  { to: '/risk', label: 'Risk console', icon: ShieldAlert },
];

export default function AppLayout() {
  const { user, logout } = useAuth();
  const { connected, dataSource, dataSourceLabel, lastTickAt } = useMarket();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="flex min-h-screen">
      <aside className="fixed inset-y-0 left-0 z-20 hidden w-60 flex-col border-r border-ink-700 bg-ink-900/80 backdrop-blur lg:flex">
        <div className="flex items-center gap-2 border-b border-ink-700 px-5 py-4">
          <BarChart3 className="text-accent" size={22} aria-hidden="true" />
          <div>
            <p className="text-base font-bold tracking-tight text-slate-100">TradeX</p>
            <p className="text-[10px] uppercase tracking-widest text-ink-500">Paper trading</p>
          </div>
        </div>
        <nav className="flex-1 space-y-1 overflow-y-auto p-3" aria-label="Primary">
          {NAV_ITEMS.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-accent/10 text-accent'
                    : 'text-slate-300 hover:bg-ink-800 hover:text-slate-100'
                }`
              }
            >
              <Icon size={17} aria-hidden="true" />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-ink-700 p-4 text-[11px] leading-snug text-ink-500">
          Virtual money only. No real brokerage connection, no real orders.
        </div>
      </aside>

      <div className="flex min-h-screen flex-1 flex-col lg:pl-60">
        <header className="sticky top-0 z-10 border-b border-ink-700 bg-ink-900/80 backdrop-blur">
          <div className="flex items-center justify-between gap-3 px-4 py-3 lg:px-6">
            <div className="flex items-center gap-3">
              <BarChart3 className="text-accent lg:hidden" size={20} aria-hidden="true" />
              <DataSourceBadge source={dataSource} label={dataSourceLabel} />
              <span
                className="hidden items-center gap-1.5 text-xs text-ink-500 sm:inline-flex"
                title={lastTickAt ? `Last tick ${new Date(lastTickAt).toLocaleTimeString()}` : 'Waiting for the first tick'}
              >
                {connected ? (
                  <>
                    <Wifi size={13} className="text-accent" aria-hidden="true" />
                    Live stream connected
                  </>
                ) : (
                  <>
                    <WifiOff size={13} className="text-warning" aria-hidden="true" />
                    Stream reconnecting…
                  </>
                )}
              </span>
            </div>
            <div className="flex items-center gap-3">
              <div className="hidden text-right sm:block">
                <p className="text-sm font-medium text-slate-100">{user?.fullName || user?.username}</p>
                <p className="text-[11px] text-ink-500">{user?.email}</p>
              </div>
              <div className="flex h-9 w-9 items-center justify-center rounded-full border border-ink-600 bg-ink-800 text-xs font-semibold text-accent">
                {initialsOf(user?.fullName || user?.username || '?')}
              </div>
              <button
                type="button"
                className="btn-ghost px-2 py-1.5"
                onClick={handleLogout}
                title="Sign out"
                aria-label="Sign out"
              >
                <LogOut size={16} aria-hidden="true" />
              </button>
            </div>
          </div>
          {String(dataSource || '').toUpperCase() !== 'LIVE' ? (
            <p className="border-t border-warning/20 bg-warning/5 px-4 py-1.5 text-center text-[11px] text-warning lg:px-6">
              SIMULATED DATA - prices are generated by the TradeX price model for practice and are <strong>not</strong> real market quotes.
            </p>
          ) : (
            <p className="border-t border-accent/20 bg-accent/5 px-4 py-1.5 text-center text-[11px] text-accent lg:px-6">
              LIVE market data: {dataSourceLabel}. Orders are still simulated - TradeX never contacts a brokerage.
            </p>
          )}
        </header>

        <nav className="flex gap-1 overflow-x-auto border-b border-ink-700 px-2 py-1.5 lg:hidden" aria-label="Primary mobile">
          {NAV_ITEMS.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                `flex shrink-0 items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium ${
                  isActive ? 'bg-accent/10 text-accent' : 'text-slate-300'
                }`
              }
            >
              <Icon size={14} aria-hidden="true" />
              {label}
            </NavLink>
          ))}
        </nav>

        <main className="flex-1 px-4 py-5 lg:px-6">
          <Outlet />
        </main>

        <footer className="border-t border-ink-700 px-4 py-3 text-center text-[11px] text-ink-500 lg:px-6">
          TradeX is an educational paper-trading simulator. Simulated results do not predict real outcomes.
        </footer>
      </div>
    </div>
  );
}
