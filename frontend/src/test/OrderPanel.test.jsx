import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OrderPanel from '../components/orders/OrderPanel.jsx';
import { ToastProvider } from '../context/ToastContext.jsx';
import { ordersApi } from '../api/orders.js';

vi.mock('../api/orders.js', () => ({
  ordersApi: {
    estimate: vi.fn(),
    place: vi.fn(),
  },
}));

const SYMBOLS = [
  { symbol: 'RELIANCE', name: 'Reliance Industries Ltd', tradable: true },
  { symbol: 'NIFTY50', name: 'NIFTY 50', tradable: false },
];

function renderPanel(props = {}) {
  return render(
    <ToastProvider>
      <OrderPanel
        symbols={SYMBOLS}
        selectedSymbol="RELIANCE"
        quote={{ lastPrice: 1288.5, changePercent: 0.42 }}
        {...props}
      />
    </ToastProvider>,
  );
}

const HAPPY_ESTIMATE = {
  symbol: 'RELIANCE',
  side: 'BUY',
  orderType: 'MARKET',
  quantity: 10,
  referencePrice: 1288.5,
  assumedExecutionPrice: 1288.5,
  orderValue: 12885,
  charges: { total: 7.03 },
  totalCost: 12892.03,
  netProceeds: 12877.97,
  availableCash: 100000,
  reservedCash: 0,
  sufficientFunds: true,
  shortfallBy: null,
  heldQuantity: 0,
  sufficientHoldings: false,
  warnings: [],
};

describe('OrderPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    ordersApi.estimate.mockResolvedValue(HAPPY_ESTIMATE);
  });

  it('blocks submission until the quantity is valid', async () => {
    const user = userEvent.setup();
    renderPanel();

    const quantity = screen.getByLabelText(/^Quantity$/i);
    await user.clear(quantity);
    await user.type(quantity, '0');

    const submit = screen.getByRole('button', { name: /buy reliance/i });
    expect(submit).toBeDisabled();

    await user.click(submit);
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(ordersApi.place).not.toHaveBeenCalled();
  });

  it('shows the server-computed estimate including charges', async () => {
    const user = userEvent.setup();
    renderPanel();

    const quantity = screen.getByLabelText(/^Quantity$/i);
    await user.clear(quantity);
    await user.type(quantity, '10');

    // The estimate request is debounced; findBy* waits for the round-trip.
    expect(await screen.findByText('Total cost')).toBeInTheDocument();
    expect(ordersApi.estimate).toHaveBeenCalledWith(
      expect.objectContaining({ symbol: 'RELIANCE', side: 'BUY', orderType: 'MARKET', quantity: 10 }),
    );
    expect(screen.getByText('₹12,892.03')).toBeInTheDocument();
    expect(screen.getByText('₹7.03')).toBeInTheDocument();
  });

  it('requires confirmation before placing and submits exactly one idempotent order', async () => {
    const user = userEvent.setup();
    const onPlaced = vi.fn();
    ordersApi.place.mockResolvedValue({
      order: { orderRef: 'ORD-TEST-1', status: 'EXECUTED' },
      message: 'Order executed.',
    });

    renderPanel({ onPlaced });

    const quantity = screen.getByLabelText(/^Quantity$/i);
    await user.clear(quantity);
    await user.type(quantity, '10');

    await user.click(screen.getByRole('button', { name: /buy reliance/i }));

    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText(/Confirm buy order/i)).toBeInTheDocument();
    expect(ordersApi.place).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole('button', { name: /place buy order/i }));

    await waitFor(() => expect(ordersApi.place).toHaveBeenCalledTimes(1));
    const [payload, idempotencyKey] = ordersApi.place.mock.calls[0];
    expect(payload).toMatchObject({
      symbol: 'RELIANCE',
      side: 'BUY',
      orderType: 'MARKET',
      quantity: 10,
    });
    expect(typeof idempotencyKey).toBe('string');
    expect(idempotencyKey.length).toBeGreaterThan(8);

    await waitFor(() => expect(onPlaced).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('Order executed.')).toBeInTheDocument();
  });

  it('surfaces a rejected order as an error toast instead of a success', async () => {
    const user = userEvent.setup();
    ordersApi.place.mockResolvedValue({
      order: { orderRef: 'ORD-TEST-2', status: 'REJECTED', rejectReason: 'Insufficient virtual balance' },
      message: 'Order rejected: Insufficient virtual balance',
    });

    renderPanel();
    const quantity = screen.getByLabelText(/^Quantity$/i);
    await user.clear(quantity);
    await user.type(quantity, '10');
    await user.click(screen.getByRole('button', { name: /buy reliance/i }));
    await user.click(await screen.findByRole('button', { name: /place buy order/i }));

    expect(await screen.findByText(/Order rejected: Insufficient virtual balance/)).toBeInTheDocument();
    expect(screen.getByText('Order rejected')).toBeInTheDocument();
  });

  it('refreshes the cash estimate when the parent bumps the refresh token', async () => {
    const user = userEvent.setup();
    const view = renderPanel({ refreshToken: 0 });

    const quantity = screen.getByLabelText(/^Quantity$/i);
    await user.clear(quantity);
    await user.type(quantity, '10');

    // Initial estimate reflects the untouched balance.
    expect(await screen.findByText('₹1,00,000.00')).toBeInTheDocument();

    // A cancellation elsewhere released cash; the server now reports more.
    ordersApi.estimate.mockResolvedValue({ ...HAPPY_ESTIMATE, availableCash: 125000 });
    view.rerender(
      <ToastProvider>
        <OrderPanel
          symbols={SYMBOLS}
          selectedSymbol="RELIANCE"
          quote={{ lastPrice: 1288.5, changePercent: 0.42 }}
          refreshToken={1}
        />
      </ToastProvider>,
    );

    expect(await screen.findByText('₹1,25,000.00')).toBeInTheDocument();
  });
});
