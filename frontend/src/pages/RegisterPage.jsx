import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { BarChart3, Lock, Mail, User, UserPlus } from 'lucide-react';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import CapitalPicker, { isValidVirtualCapital } from '../components/account/CapitalPicker.jsx';

const USERNAME_PATTERN = /^[A-Za-z0-9._-]+$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function RegisterPage() {
  const { register } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [form, setForm] = useState({
    fullName: '',
    email: '',
    username: '',
    password: '',
    confirmPassword: '',
    initialCapital: '10000',
  });
  const [errors, setErrors] = useState({});
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);

  const update = (field) => (event) => setForm((f) => ({ ...f, [field]: event.target.value }));

  const validate = () => {
    const next = {};
    if (!form.fullName.trim()) next.fullName = 'Tell us your name.';
    if (!EMAIL_PATTERN.test(form.email.trim())) next.email = 'Enter a valid email address.';
    if (form.username.trim().length < 3 || form.username.trim().length > 60) {
      next.username = 'Username must be 3-60 characters.';
    } else if (!USERNAME_PATTERN.test(form.username.trim())) {
      next.username = 'Letters, digits, dot, underscore and hyphen only.';
    }
    if (form.password.length < 8) next.password = 'Password must be at least 8 characters.';
    if (form.password !== form.confirmPassword) next.confirmPassword = 'Passwords do not match.';
    if (!isValidVirtualCapital(form.initialCapital)) {
      next.initialCapital = 'Enter an amount from $100 to $1,000,000, with up to 2 decimal places.';
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const submit = async (event) => {
    event.preventDefault();
    setFormError(null);
    if (!validate()) return;
    setBusy(true);
    try {
      await register({
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        username: form.username.trim(),
        password: form.password,
        initialCapital: Number(form.initialCapital),
      });
      toast.success(`Account created with ${new Intl.NumberFormat('en-US', {
        style: 'currency', currency: 'USD', maximumFractionDigits: 2,
      }).format(Number(form.initialCapital))} of virtual capital.`, 'Welcome to TradeX');
      navigate('/', { replace: true });
    } catch (apiFailure) {
      if (apiFailure.fieldErrors) {
        setErrors(
          Object.fromEntries(
            Object.entries(apiFailure.fieldErrors).map(([field, message]) => [field, message]),
          ),
        );
      }
      setFormError(apiFailure.message || 'Registration failed. Please try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <div className="w-full max-w-lg">
        <div className="mb-6 flex items-center justify-center gap-2">
          <BarChart3 className="text-accent" size={26} aria-hidden="true" />
          <div>
            <h1 className="text-xl font-bold tracking-tight text-slate-100">TradeX</h1>
            <p className="text-[11px] uppercase tracking-widest text-ink-500">Virtual trading simulator</p>
          </div>
        </div>

        <form onSubmit={submit} className="panel space-y-4 p-6" noValidate>
          <div>
            <h2 className="text-lg font-semibold text-slate-100">Create your account</h2>
            <p className="mt-1 text-sm text-ink-500">
              Configure a starting paper-trading balance. You can change it later only by explicitly
              resetting your virtual account.
            </p>
          </div>

          {formError ? (
            <div className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger-soft" role="alert">
              {formError}
            </div>
          ) : null}

          <Field
            id="fullName"
            label="Full name"
            icon={User}
            value={form.fullName}
            onChange={update('fullName')}
            error={errors.fullName}
            autoComplete="name"
            placeholder="Asha Rao"
          />
          <Field
            id="email"
            label="Email"
            type="email"
            icon={Mail}
            value={form.email}
            onChange={update('email')}
            error={errors.email}
            autoComplete="email"
            placeholder="you@example.com"
          />
          <Field
            id="username"
            label="Username"
            icon={User}
            value={form.username}
            onChange={update('username')}
            error={errors.username}
            autoComplete="username"
            placeholder="asha.rao"
          />
          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              id="password"
              label="Password"
              type="password"
              icon={Lock}
              value={form.password}
              onChange={update('password')}
              error={errors.password}
              autoComplete="new-password"
              placeholder="At least 8 characters"
            />
            <Field
              id="confirmPassword"
              label="Confirm password"
              type="password"
              icon={Lock}
              value={form.confirmPassword}
              onChange={update('confirmPassword')}
              error={errors.confirmPassword}
              autoComplete="new-password"
              placeholder="Repeat password"
            />
          </div>

          <section className="rounded-xl border border-ink-700 p-4" aria-labelledby="capital-heading">
            <h3 id="capital-heading" className="mb-3 text-sm font-semibold text-slate-100">
              Choose Your Virtual Capital
            </h3>
            <CapitalPicker
              value={form.initialCapital}
              onChange={(initialCapital) => setForm((f) => ({ ...f, initialCapital }))}
              error={errors.initialCapital}
            />
          </section>

          <button type="submit" className="btn-accent w-full" disabled={busy}>
            <UserPlus size={16} aria-hidden="true" />
            {busy ? 'Creating account…' : 'Create account'}
          </button>

          <p className="text-center text-sm text-ink-500">
            Already registered?{' '}
            <Link to="/login" className="link-accent">
              Sign in
            </Link>
          </p>
        </form>
      </div>
    </div>
  );
}

function Field({ id, label, icon: Icon, error, ...inputProps }) {
  return (
    <div>
      <label className="label" htmlFor={id}>
        {label}
      </label>
      <div className="relative">
        {Icon ? (
          <Icon size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-500" aria-hidden="true" />
        ) : null}
        <input
          id={id}
          name={id}
          className={`input ${Icon ? 'pl-9' : ''} ${error ? 'border-danger' : ''}`}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? `${id}-error` : undefined}
          {...inputProps}
        />
      </div>
      {error ? (
        <p id={`${id}-error`} className="mt-1 text-xs text-danger-soft" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
