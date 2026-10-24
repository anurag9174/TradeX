import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import PortfolioPage from '../pages/PortfolioPage.jsx';
import { ToastProvider } from '../context/ToastContext.jsx';
import { portfolioApi } from '../api/portfolio.js';
import { accountApi } from '../api/account.js';

vi.mock('../api/portfolio.js', () => ({
  portfolioApi: {
    portfolio: vi.fn(),
    performance: vi.fn(),
  },
}));

vi.mock('../api/account.js', () => ({
  accountApi: {
    account: vi.fn(),
    reset: vi.fn(),
  },
}));

vi.mock('../context/MarketContext.jsx', () => ({
  useMarket: () => ({
    quotes: {},
    subscribe: () => () => {},
    connected: false,
    status: { dataSource: 'SIMULATED', dataSourceLabel: 'SIMULATED' },
  }),
}));

const PORTFOLIO = {
  accountNumber: 'VIRT-000001',
  currency: 'INR',
  availableCash: 60000,
  reservedCash: 0,
  totalCash: 60000,
  investedValue: 40000,
  marketValue: 41000,
  portfolioValue: 101000,
  unrealizedPnl: 1000,
  unrealizedPnlPercent: 2.5,
  realizedPnl: 0,
  totalReturn: 1000,
  totalReturnPercent: 1,
  todaysPnl: 1000,
  positionCount: 2,
  openOrderCount: 0,
  dataSource: 'SIMULATED',
  dataSourceLabel: 'SIMULATED',
  holdings: [
    {
      id: 1,
      symbol: 'RELIANCE',
      name: 'Reliance Industries Ltd',
      exchange: 'NSE',
      assetClass: 'EQUITY',
      currency: 'INR',
      quantity: 20,
      avgCost: 1000,
      currentPrice: 1025,
      priceAvailable: true,
      investedValue: 20000,
      marketValue: 20500,
      unrealizedPnl: 500,
      unrealizedPnlPercent: 2.5,
      realizedPnl: 0,
      allocationPercent: 50,
      stopLossPrice: 950,
      targetPrice: 1200,
      openedAt: '2026-10-01T05:00:00Z',
      lastTradedAt: '2026-10-01T05:00:00Z',
    },
    {
      id: 2,
      symbol: 'TCS',
      name: 'Tata Consultancy Services Ltd',
      exchange: 'NSE',
      assetClass: 'EQUITY',
      currency: 'INR',
      quantity: 10,
      avgCost: 2000,
      currentPrice: 2000,
      priceAvailable: true,
      investedValue: 20000,
      marketValue: 20000,
      unrealizedPnl: 0,
      unrealizedPnlPercent: 0,
      realizedPnl: 0,
      allocationPercent: 50,
      stopLossPrice: null,
      targetPrice: null,
      openedAt: '2026-10-02T05:00:00Z',
      lastTradedAt: '2026-10-02T05:00:00Z',
    },
  ],
  allocation: [
    { symbol: 'RELIANCE', name: 'Reliance Industries Ltd', marketValue: 20500, percent: 50 },
    { symbol: 'TCS', name: 'Tata Consultancy Services Ltd', marketValue: 20000, percent: 50 },
  ],
  asOf: '2026-10-15T05:00:00Z',
};

const PERFORMANCE = {
  accountNumber: 'VIRT-000001',
  currency: 'INR',
  from: '2026-09-16',
  to: '2026-10-15',
  startingValue: 100000,
  endingValue: 101000,
  netChange: 1000,
  netChangePercent: 1,
  realizedPnl: 0,
  unrealizedPnl: 1000,
  totalCharges: 42,
  investedCapital: 40000,
  tradeCount: 2,
  winningTrades: 0,
  losingTrades: 0,
  winRate: 0,
  averageWin: 0,
  averageLoss: 0,
  profitFactor: 0,
  bestTrade: 0,
  worstTrade: 0,
  maxDrawdown: 0,
  maxDrawdownPercent: 0,
  equityCurve: [
    { date: '2026-10-14', portfolioValue: 100500, cash: 60000, invested: 40500, realizedPnl: 0 },
    { date: '2026-10-15', portfolioValue: 101000, cash: 60000, invested: 41000, realizedPnl: 0 },
  ],
  dailyPnl: [],
  byInstrument: [],
};

function renderPage() {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <PortfolioPage />
      </ToastProvider>
    </MemoryRouter>,
  );
}

describe('PortfolioPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    portfolioApi.portfolio.mockResolvedValue(PORTFOLIO);
    portfolioApi.performance.mockResolvedValue(PERFORMANCE);
    accountApi.account.mockResolvedValue({ id: 7 });
  });

  it('renders the valued holdings table with average cost and unrealised P&L', async () => {
    renderPage();

    expect(await screen.findByText('RELIANCE')).toBeInTheDocument();
    expect(screen.getByText('TCS')).toBeInTheDocument();

    // Scope the money assertions to the holdings table: the metric cards above it legitimately
    // show the same formatted amounts (portfolio-level P&L is also ₹1,000.00 here).
    const table = within(screen.getByRole('table'));

    // Average cost, market value and the computed P&L come straight from the server payload.
    expect(table.getByText('₹1,000.00')).toBeInTheDocument();
    expect(table.getAllByText('₹20,500.00').length).toBeGreaterThan(0);
    // The P&L cell prefixes the amount with a textual direction arrow, so match on substring.
    expect(table.getByText(/₹500\.00/)).toBeInTheDocument();
    expect(table.getByText('+2.50%')).toBeInTheDocument();

    // Risk plan column renders the attached stop/target in text, not colour-only.
    expect(table.getByText(/Stop ₹950\.00/)).toBeInTheDocument();
  });

  it('resets the virtual account only after explicit confirmation', async () => {
    const user = userEvent.setup();
    accountApi.reset.mockResolvedValue({
      accountNumber: 'VIRT-000001',
      availableCash: 100000,
      message: 'Simulated account restarted with 100000.0000 INR.',
    });

    renderPage();
    await screen.findByText('RELIANCE');

    await user.click(screen.getByRole('button', { name: /reset account/i }));
    const dialog = await screen.findByRole('alertdialog');
    expect(accountApi.reset).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole('button', { name: '$25,000' }));
    await user.click(within(dialog).getByRole('button', { name: /reset to \$25,000\.00/i }));

    await waitFor(() => expect(accountApi.reset).toHaveBeenCalledWith({
      confirm: true,
      initialCapital: 25000,
    }));
    expect(await screen.findByText(/Simulated account restarted/)).toBeInTheDocument();
  });
});
