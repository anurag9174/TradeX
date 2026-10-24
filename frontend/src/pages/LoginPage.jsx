import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { BarChart3, Lock, LogIn, Mail } from 'lucide-react';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';

export default function LoginPage() {
  const { login } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const [form, setForm] = useState({ identifier: '', password: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const update = (field) => (event) => setForm((f) => ({ ...f, [field]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    setError(null);
    if (!form.identifier.trim() || !form.password) {
      setError('Enter your email (or username) and password.');
      return;
    }
    setBusy(true);
    try {
      await login(form.identifier.trim(), form.password);
      toast.success('Welcome back to TradeX.');
      navigate(location.state?.from || '/', { replace: true });
    } catch (apiFailure) {
      setError(apiFailure.message || 'Sign-in failed. Check your credentials and try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center justify-center gap-2">
          <BarChart3 className="text-accent" size={26} aria-hidden="true" />
          <div>
            <h1 className="text-xl font-bold tracking-tight text-slate-100">TradeX</h1>
            <p className="text-[11px] uppercase tracking-widest text-ink-500">Virtual trading simulator</p>
          </div>
        </div>

        <form onSubmit={submit} className="panel space-y-4 p-6" noValidate>
          <div>
            <h2 className="text-lg font-semibold text-slate-100">Sign in</h2>
            <p className="mt-1 text-sm text-ink-500">
              Practice with your chosen virtual capital. No real orders, ever.
            </p>
          </div>

          {error ? (
            <div className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger-soft" role="alert">
              {error}
            </div>
          ) : null}

          <div>
            <label className="label" htmlFor="identifier">
              Email or username
            </label>
            <div className="relative">
              <Mail size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-500" aria-hidden="true" />
              <input
                id="identifier"
                name="identifier"
                type="text"
                autoComplete="username"
                className="input pl-9"
                value={form.identifier}
                onChange={update('identifier')}
                placeholder="you@example.com"
              />
            </div>
          </div>

          <div>
            <label className="label" htmlFor="password">
              Password
            </label>
            <div className="relative">
              <Lock size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-500" aria-hidden="true" />
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                className="input pl-9"
                value={form.password}
                onChange={update('password')}
                placeholder="••••••••"
              />
            </div>
          </div>

          <button type="submit" className="btn-accent w-full" disabled={busy}>
            <LogIn size={16} aria-hidden="true" />
            {busy ? 'Signing in…' : 'Sign in'}
          </button>

          <p className="text-center text-sm text-ink-500">
            New here?{' '}
            <Link to="/register" className="link-accent">
              Create an account
            </Link>{' '}
            and receive one virtual ₹1,00,000 grant.
          </p>
        </form>

        <p className="mt-4 text-center text-[11px] leading-relaxed text-ink-500">
          TradeX is a paper-trading simulator for education. Prices are simulated unless a live data
          provider is configured, and no brokerage account is ever contacted.
        </p>
      </div>
    </div>
  );
}
