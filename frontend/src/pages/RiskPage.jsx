import { useCallback, useEffect, useMemo, useState } from 'react';
import { Calculator, Plus, Save, ShieldAlert, Trash2 } from 'lucide-react';
import { riskApi } from '../api/risk.js';
import { apiError } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import Spinner from '../components/ui/Spinner.jsx';
import ErrorState from '../components/ui/ErrorState.jsx';
import EmptyState from '../components/ui/EmptyState.jsx';
import { formatDateTime, formatMoney, formatNumber, formatPercent, formatQuantity } from '../lib/format.js';

const EMPTY_JOURNAL = { title: '', note: '', tag: '', symbol: '', riskAmount: '', rewardAmount: '' };
const EMPTY_SIZE = { symbol: '', entryPrice: '', stopPrice: '', targetPrice: '', riskPercent: '1', maxAllocationPercent: '25', lotSize: '1' };

export default function RiskPage() {
  const toast = useToast();
  const [summary, setSummary] = useState(null);
  const [journal, setJournal] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [settings, setSettings] = useState({ dailyLossLimit: '', dailyLossLimitEnabled: false, tradingBlocked: false });
  const [savingSettings, setSavingSettings] = useState(false);
  const [sizeForm, setSizeForm] = useState(EMPTY_SIZE);
  const [sizeResult, setSizeResult] = useState(null);
  const [sizeError, setSizeError] = useState(null);
  const [journalForm, setJournalForm] = useState(EMPTY_JOURNAL);
  const [savingJournal, setSavingJournal] = useState(false);

  const load = useCallback(async (showSpinner = false) => {
    if (showSpinner) setLoading(true);
    try {
      const [summaryData, journalData] = await Promise.all([riskApi.summary(), riskApi.journal({ size: 20 })]);
      setSummary(summaryData);
      setJournal(journalData);
      setSettings({
        dailyLossLimit: summaryData.dailyLossLimit ?? '',
        dailyLossLimitEnabled: Boolean(summaryData.dailyLossLimitEnabled),
        tradingBlocked: Boolean(summaryData.tradingBlocked),
      });
      setError(null);
    } catch (failure) {
      setError(apiError(failure));
    } finally {
      if (showSpinner) setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(true);
  }, [load]);

  const saveSettings = async (event) => {
    event.preventDefault();
    setSavingSettings(true);
    try {
      await riskApi.updateSettings({
        dailyLossLimit: settings.dailyLossLimit === '' ? null : Number(settings.dailyLossLimit),
        dailyLossLimitEnabled: settings.dailyLossLimitEnabled,
        tradingBlocked: settings.tradingBlocked,
        clearBreach: false,
      });
      toast.success('Risk settings saved.');
      await load(false);
    } catch (failure) {
      toast.error(apiError(failure).message, 'Could not save settings');
    } finally {
      setSavingSettings(false);
    }
  };

  const clearBreach = async () => {
    try {
      await riskApi.updateSettings({ clearBreach: true, tradingBlocked: false });
      toast.success('Daily-loss breach cleared. Trading resumed.');
      await load(false);
    } catch (failure) {
      toast.error(apiError(failure).message, 'Could not clear the breach');
    }
  };

  const calculate = async (event) => {
    event.preventDefault();
    setSizeError(null);
    const payload = {
      symbol: sizeForm.symbol ? sizeForm.symbol.trim().toUpperCase() : null,
      entryPrice: Number(sizeForm.entryPrice),
      stopPrice: Number(sizeForm.stopPrice),
      targetPrice: sizeForm.targetPrice ? Number(sizeForm.targetPrice) : null,
      riskPercent: Number(sizeForm.riskPercent),
      maxAllocationPercent: sizeForm.maxAllocationPercent ? Number(sizeForm.maxAllocationPercent) : null,
      lotSize: sizeForm.lotSize ? Number(sizeForm.lotSize) : null,
    };
    if (!payload.entryPrice || !payload.stopPrice || !payload.riskPercent) {
      setSizeError('Entry, stop and risk percent are required.');
      return;
    }
    try {
      setSizeResult(await riskApi.positionSize(payload));
    } catch (failure) {
      setSizeError(apiError(failure).message);
      setSizeResult(null);
    }
  };

  const addJournal = async (event) => {
    event.preventDefault();
    if (!journalForm.title.trim()) return;
    setSavingJournal(true);
    try {
      await riskApi.addJournal({
        title: journalForm.title.trim(),
        note: journalForm.note || null,
        tag: journalForm.tag || null,
        symbol: journalForm.symbol ? journalForm.symbol.toUpperCase() : null,
        riskAmount: journalForm.riskAmount ? Number(journalForm.riskAmount) : null,
        rewardAmount: journalForm.rewardAmount ? Number(journalForm.rewardAmount) : null,
      });
      toast.success('Journal entry saved.');
      setJournalForm(EMPTY_JOURNAL);
      await load(false);
    } catch (failure) {
      toast.error(apiError(failure).message, 'Could not save the entry');
    } finally {
      setSavingJournal(false);
    }
  };

  const removeJournal = async (id) => {
    try {
      await riskApi.deleteJournal(id);
      toast.info('Journal entry deleted.');
      await load(false);
    } catch (failure) {
      toast.error(apiError(failure).message, 'Could not delete');
    }
  };

  const openPositions = summary?.openPositions || [];
  const accountCurrency = summary?.currency || 'USD';
  const lossLimit = Number(summary?.dailyLossLimit ?? 0);
  const lossUsed = Number(summary?.dailyLossUsed ?? 0);
  const lossPercent = lossLimit > 0 ? Math.min(100, (lossUsed / lossLimit) * 100) : 0;

  if (loading && !summary) {
    return <Spinner label="Assessing your risk…" />;
  }
  if (error && !summary) {
    return <ErrorState message={error.message} onRetry={() => load(true)} />;
  }

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold text-slate-100">Risk console</h1>
        <p className="mt-0.5 text-sm text-ink-500">
          Plan the trade before you take it: position size, exposure, daily-loss guard and your journal.
        </p>
      </header>

      {summary?.warnings?.length ? (
        <div className="panel border-warning/40 p-3">
          <p className="flex items-center gap-2 text-sm font-medium text-warning">
            <ShieldAlert size={15} aria-hidden="true" />
            Risk warnings
          </p>
          <ul className="mt-2 list-disc space-y-1 pl-6 text-xs text-slate-300">
            {summary.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Card label="Equity" value={formatMoney(summary?.portfolioValue, accountCurrency)} />
        <Card label="Exposure" value={formatPercent(summary?.exposurePercent, { signed: false })} sub={formatMoney(summary?.investedValue, accountCurrency)} />
        <Card
          label="Today's P&L"
          value={formatMoney(summary?.todaysTotalPnl, accountCurrency)}
          tone={Number(summary?.todaysTotalPnl ?? 0) >= 0 ? 'up' : 'down'}
          sub={`Charges ${formatMoney(summary?.todaysCharges, accountCurrency)}`}
        />
        <Card
          label="Largest position"
          value={summary?.largestPositionSymbol ? formatPercent(summary.largestPositionPercent, { signed: false }) : '—'}
          sub={summary?.largestPositionSymbol || 'No positions'}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <section className="panel" aria-labelledby="guard-heading">
          <div className="panel-header">
            <h2 id="guard-heading" className="text-sm font-semibold text-slate-100">
              Daily-loss guard
            </h2>
            {summary?.limitBreached ? (
              <span className="badge border border-danger/40 bg-danger/10 text-danger-soft">Breached</span>
            ) : (
              <span className="badge-neutral">{summary?.dailyLossLimitEnabled ? 'Armed' : 'Off'}</span>
            )}
          </div>
          <form className="space-y-3 p-4" onSubmit={saveSettings}>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="label" htmlFor="daily-loss-limit">
                  Max daily loss ({accountCurrency === 'USD' ? '$' : '₹'})
                </label>
                <input
                  id="daily-loss-limit"
                  type="number"
                  min="0"
                  step="100"
                  className="input num"
                  value={settings.dailyLossLimit}
                  onChange={(event) => setSettings((s) => ({ ...s, dailyLossLimit: event.target.value }))}
                />
              </div>
              <div className="flex flex-col justify-end gap-2 pb-1">
                <label className="flex items-center gap-2 text-sm text-slate-300">
                  <input
                    type="checkbox"
                    className="h-4 w-4 rounded border-ink-600 bg-ink-900 accent-accent"
                    checked={settings.dailyLossLimitEnabled}
                    onChange={(event) => setSettings((s) => ({ ...s, dailyLossLimitEnabled: event.target.checked }))}
                  />
                  Block new orders when breached
                </label>
                <label className="flex items-center gap-2 text-sm text-slate-300">
                  <input
                    type="checkbox"
                    className="h-4 w-4 rounded border-ink-600 bg-ink-900 accent-accent"
                    checked={settings.tradingBlocked}
                    onChange={(event) => setSettings((s) => ({ ...s, tradingBlocked: event.target.checked }))}
                  />
                  Manually pause trading
                </label>
              </div>
            </div>

            <div>
              <div className="mb-1 flex items-center justify-between text-xs text-ink-500">
                <span>Used today</span>
                <span className="num">
                  {formatMoney(lossUsed, accountCurrency)} {lossLimit > 0 ? `/ ${formatMoney(lossLimit, accountCurrency)}` : ''}
                </span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-ink-700">
                <div
                  className={`h-full rounded-full ${lossPercent >= 100 ? 'bg-danger' : 'bg-accent'}`}
                  style={{ width: `${lossLimit > 0 ? lossPercent : 0}%` }}
                />
              </div>
              <p className="mt-1 text-[11px] text-ink-500">
                Breaching the limit blocks new risk-increasing orders until the next trading day or
                until you clear it below. Protective exits can still fire.
              </p>
            </div>

            <div className="flex flex-wrap gap-2">
              <button type="submit" className="btn-accent" disabled={savingSettings}>
                <Save size={14} aria-hidden="true" />
                {savingSettings ? 'Saving…' : 'Save settings'}
              </button>
              {summary?.limitBreached || summary?.tradingBlocked ? (
                <button type="button" className="btn-outline" onClick={clearBreach}>
                  Clear breach &amp; resume
                </button>
              ) : null}
            </div>
          </form>
        </section>

        <section className="panel" aria-labelledby="sizing-heading">
          <div className="panel-header">
            <h2 id="sizing-heading" className="text-sm font-semibold text-slate-100">
              Position-size calculator
            </h2>
            <Calculator size={15} className="text-ink-500" aria-hidden="true" />
          </div>
          <form className="grid grid-cols-2 gap-3 p-4" onSubmit={calculate}>
            <div className="col-span-2">
              <label className="label" htmlFor="size-symbol">
                Symbol (optional)
              </label>
              <input
                id="size-symbol"
                className="input uppercase"
                value={sizeForm.symbol}
                onChange={(event) => setSizeForm((f) => ({ ...f, symbol: event.target.value }))}
                placeholder="e.g. BTCUSD — sets the price currency"
              />
            </div>
            <NumberField id="size-entry" label="Entry price" value={sizeForm.entryPrice} onChange={(v) => setSizeForm((f) => ({ ...f, entryPrice: v }))} />
            <NumberField id="size-stop" label="Stop-loss price" value={sizeForm.stopPrice} onChange={(v) => setSizeForm((f) => ({ ...f, stopPrice: v }))} />
            <NumberField id="size-target" label="Target price (optional)" value={sizeForm.targetPrice} onChange={(v) => setSizeForm((f) => ({ ...f, targetPrice: v }))} />
            <NumberField id="size-risk" label="Risk % of portfolio" value={sizeForm.riskPercent} onChange={(v) => setSizeForm((f) => ({ ...f, riskPercent: v }))} />
            <NumberField id="size-alloc" label="Max allocation %" value={sizeForm.maxAllocationPercent} onChange={(v) => setSizeForm((f) => ({ ...f, maxAllocationPercent: v }))} />
            <NumberField id="size-lot" label="Lot size" value={sizeForm.lotSize} onChange={(v) => setSizeForm((f) => ({ ...f, lotSize: v }))} />
            <div className="col-span-2">
              <button type="submit" className="btn-accent w-full">
                Calculate position size
              </button>
            </div>
          </form>
          {sizeError ? (
            <p className="px-4 pb-3 text-sm text-danger-soft" role="alert">
              {sizeError}
            </p>
          ) : null}
          {sizeResult ? (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 border-t border-ink-700 px-4 py-3 text-xs">
              <Result label="Suggested quantity" value={formatQuantity(sizeResult.suggestedQuantity)} strong />
              <Result label="Notional" value={formatMoney(sizeResult.suggestedNotional, sizeResult.ledgerCurrency)} strong />
              <Result label="Capital at risk" value={formatMoney(sizeResult.capitalAtRisk, sizeResult.ledgerCurrency)} />
              <Result label="Of portfolio" value={formatPercent(sizeResult.suggestedAllocationPercent, { signed: false })} />
              <Result label="Risk per unit" value={formatMoney(sizeResult.riskPerUnit, sizeResult.priceCurrency)} />
              <Result label="Reward : risk" value={sizeResult.riskRewardRatio != null ? `${formatNumber(sizeResult.riskRewardRatio, 2)} : 1` : '—'} />
              <Result label="Potential profit" value={formatMoney(sizeResult.potentialProfit, sizeResult.ledgerCurrency)} />
              <Result label="Potential loss" value={formatMoney(sizeResult.potentialLoss, sizeResult.ledgerCurrency)} />
              <Result label="Break-even price" value={formatMoney(sizeResult.breakEvenPrice, sizeResult.priceCurrency)} />
              <Result label="Max by allocation" value={formatQuantity(sizeResult.maxQuantityByAllocation)} />
              <p className="col-span-2 text-[11px] text-ink-500">{sizeResult.rationale}</p>
              {sizeResult.fxRate != null && Number(sizeResult.fxRate) !== 1 ? (
                <p className="col-span-2 text-[11px] text-ink-500">
                  Prices are entered in {sizeResult.priceCurrency}; cash amounts are converted to{' '}
                  {sizeResult.ledgerCurrency} at the nominal rate 1 {sizeResult.priceCurrency} ={' '}
                  {formatNumber(sizeResult.fxRate, 4)} {sizeResult.ledgerCurrency}.
                </p>
              ) : null}
            </dl>
          ) : null}
        </section>
      </div>

      <section className="panel overflow-hidden" aria-labelledby="open-risk-heading">
        <div className="panel-header">
          <h2 id="open-risk-heading" className="text-sm font-semibold text-slate-100">
            Open-position risk
          </h2>
          <span className="text-[11px] text-ink-500">
            Total capital at stop: {formatMoney(summary?.totalOpenRisk, accountCurrency)}
          </span>
        </div>
        {openPositions.length ? (
          <div className="overflow-x-auto">
            <table className="table-base">
              <thead>
                <tr>
                  <th scope="col">Instrument</th>
                  <th scope="col" className="text-right">Qty</th>
                  <th scope="col" className="text-right">Avg cost</th>
                  <th scope="col" className="text-right">Last</th>
                  <th scope="col" className="text-right">Unrealised</th>
                  <th scope="col" className="text-right">Stop / Target</th>
                  <th scope="col" className="text-right">Risk at stop</th>
                  <th scope="col" className="text-right">R : R</th>
                  <th scope="col">Note</th>
                </tr>
              </thead>
              <tbody>
                {openPositions.map((position) => (
                  <tr key={position.symbol}>
                    <td>
                      <p className="font-medium text-slate-100">{position.symbol}</p>
                      <p className="text-xs text-ink-500">{position.name}</p>
                    </td>
                    <td className="num text-right">{formatQuantity(position.quantity)}</td>
                    <td className="num text-right">{formatMoney(position.avgCost, position.currency)}</td>
                    <td className="num text-right">{formatMoney(position.currentPrice, position.currency)}</td>
                    <td className="num text-right">
                      <span className={Number(position.unrealizedPnl) >= 0 ? 'text-accent' : 'text-danger-soft'}>
                        <span aria-hidden="true">{Number(position.unrealizedPnl) >= 0 ? '▲ ' : '▼ '}</span>
                        {formatMoney(position.unrealizedPnl, accountCurrency)}
                      </span>
                    </td>
                    <td className="num text-right">
                      {position.stopLossPrice != null ? formatMoney(position.stopLossPrice, position.currency) : '—'}
                      {' / '}
                      {position.targetPrice != null ? formatMoney(position.targetPrice, position.currency) : '—'}
                    </td>
                    <td className="num text-right">
                      {position.riskAmountAtStop != null ? formatMoney(position.riskAmountAtStop, accountCurrency) : '—'}
                      {position.capitalAtRiskPercent != null ? (
                        <p className="text-xs text-ink-500">{formatPercent(position.capitalAtRiskPercent, { signed: false })}</p>
                      ) : null}
                    </td>
                    <td className="num text-right">
                      {position.riskRewardRatio != null ? `${formatNumber(position.riskRewardRatio, 2)} : 1` : '—'}
                    </td>
                    <td className="max-w-[240px] text-xs text-ink-500">{position.riskNote}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="No open positions"
            description="Risk per position appears here once you hold a position, especially with an attached stop-loss."
          />
        )}
        {summary?.fxNote ? (
          <p className="border-t border-ink-700 px-4 py-2 text-[11px] text-ink-500">{summary.fxNote}</p>
        ) : null}
      </section>

      <section className="panel" aria-labelledby="journal-heading">
        <div className="panel-header">
          <h2 id="journal-heading" className="text-sm font-semibold text-slate-100">
            Trade journal
          </h2>
          <span className="text-[11px] text-ink-500">{journal?.totalElements ?? 0} entries</span>
        </div>
        <form className="grid grid-cols-2 gap-3 border-b border-ink-700 p-4 md:grid-cols-6" onSubmit={addJournal}>
          <div className="col-span-2">
            <label className="label" htmlFor="journal-title">
              Title
            </label>
            <input
              id="journal-title"
              className="input"
              value={journalForm.title}
              onChange={(event) => setJournalForm((f) => ({ ...f, title: event.target.value }))}
              placeholder="Why I took this trade"
            />
          </div>
          <div>
            <label className="label" htmlFor="journal-symbol">
              Symbol
            </label>
            <input
              id="journal-symbol"
              className="input uppercase"
              value={journalForm.symbol}
              onChange={(event) => setJournalForm((f) => ({ ...f, symbol: event.target.value }))}
              placeholder="TCS"
            />
          </div>
          <div>
            <label className="label" htmlFor="journal-tag">
              Tag
            </label>
            <input
              id="journal-tag"
              className="input"
              value={journalForm.tag}
              onChange={(event) => setJournalForm((f) => ({ ...f, tag: event.target.value }))}
              placeholder="breakout"
            />
          </div>
          <div>
            <label className="label" htmlFor="journal-risk">
              Risk ₹
            </label>
            <input
              id="journal-risk"
              type="number"
              min="0"
              className="input num"
              value={journalForm.riskAmount}
              onChange={(event) => setJournalForm((f) => ({ ...f, riskAmount: event.target.value }))}
            />
          </div>
          <div>
            <label className="label" htmlFor="journal-reward">
              Reward ₹
            </label>
            <input
              id="journal-reward"
              type="number"
              min="0"
              className="input num"
              value={journalForm.rewardAmount}
              onChange={(event) => setJournalForm((f) => ({ ...f, rewardAmount: event.target.value }))}
            />
          </div>
          <div className="col-span-2 md:col-span-6">
            <label className="label" htmlFor="journal-note">
              Note
            </label>
            <textarea
              id="journal-note"
              className="input min-h-[70px]"
              value={journalForm.note}
              onChange={(event) => setJournalForm((f) => ({ ...f, note: event.target.value }))}
              placeholder="What was the setup? What will invalidate it?"
            />
          </div>
          <div className="col-span-2 md:col-span-6">
            <button type="submit" className="btn-accent" disabled={savingJournal || !journalForm.title.trim()}>
              <Plus size={14} aria-hidden="true" />
              {savingJournal ? 'Saving…' : 'Add journal entry'}
            </button>
          </div>
        </form>

        {journal?.content?.length ? (
          <ul className="divide-y divide-ink-800">
            {journal.content.map((entry) => (
              <li key={entry.id} className="flex items-start justify-between gap-4 px-4 py-3">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-slate-100">
                    {entry.title}
                    {entry.symbol ? <span className="ml-2 text-xs text-accent">{entry.symbol}</span> : null}
                    {entry.tag ? <span className="badge-neutral ml-2">{entry.tag}</span> : null}
                  </p>
                  {entry.note ? <p className="mt-1 text-xs text-ink-500">{entry.note}</p> : null}
                  <p className="mt-1 text-[11px] text-ink-500">
                    {formatDateTime(entry.createdAt)}
                    {entry.riskAmount != null ? ` · Risk ${formatMoney(entry.riskAmount, accountCurrency)}` : ''}
                    {entry.rewardAmount != null ? ` · Reward ${formatMoney(entry.rewardAmount, accountCurrency)}` : ''}
                    {entry.riskRewardRatio != null ? ` · ${formatNumber(entry.riskRewardRatio, 2)} : 1` : ''}
                  </p>
                </div>
                <button
                  type="button"
                  className="btn-ghost px-2 py-1 text-danger-soft"
                  onClick={() => removeJournal(entry.id)}
                  aria-label={`Delete journal entry ${entry.title}`}
                >
                  <Trash2 size={15} aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="px-4 py-6 text-center text-sm text-ink-500">
            No journal entries yet. Writing down the plan is the cheapest risk control there is.
          </p>
        )}
      </section>

      <p className="text-[11px] text-ink-500">{summary?.disclaimer}</p>
    </div>
  );
}

function Card({ label, value, sub, tone }) {
  return (
    <div className="panel p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500">{label}</p>
      <p
        className={`num mt-1 text-lg font-semibold ${
          tone === 'up' ? 'text-accent' : tone === 'down' ? 'text-danger-soft' : 'text-slate-100'
        }`}
      >
        {value}
      </p>
      {sub ? <p className="num mt-0.5 text-xs text-ink-500">{sub}</p> : null}
    </div>
  );
}

function NumberField({ id, label, value, onChange }) {
  return (
    <div>
      <label className="label" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        type="number"
        min="0"
        step="any"
        className="input num"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

function Result({ label, value, strong = false }) {
  return (
    <div>
      <dt className="text-ink-500">{label}</dt>
      <dd className={`num ${strong ? 'font-semibold text-slate-100' : 'text-slate-300'}`}>{value}</dd>
    </div>
  );
}
