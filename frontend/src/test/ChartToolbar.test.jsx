import { describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import ChartToolbar from '../components/chart/ChartToolbar.jsx';

function renderToolbar(timeframe) {
  return render(
    <ChartToolbar
      chartType="candles"
      onChartType={() => {}}
      timeframe={timeframe}
      onTimeframe={() => {}}
      indicatorOptions={[]}
      activeIndicators={[]}
      onToggleIndicator={() => {}}
    />,
  );
}

describe('ChartToolbar candle countdown', () => {
  it.each(['1m', '5m', '15m', '30m', '1h', '4h', '1d', '1w'])(
    'shows the next-candle countdown for %s',
    (timeframe) => {
      renderToolbar(timeframe);
      const countdown = screen.getByTestId('candle-countdown');
      expect(countdown).toHaveTextContent(`${timeframe.toUpperCase()} · Current candle closes at:`);
      expect(countdown).toHaveTextContent(/Time remaining: \d{2,}:\d{2}(?::\d{2})? · Next candle starts at:/);
    },
  );

  it('ticks once per second without depending on incoming market events', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2024-01-10T12:34:28Z'));
    const view = renderToolbar('1m');
    const countdown = screen.getByTestId('candle-countdown');
    const formatLocal = (date) =>
      new Intl.DateTimeFormat(undefined, {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: true,
        timeZoneName: 'short',
      }).format(date);
    expect(countdown).toHaveTextContent('Time remaining: 00:32');
    expect(countdown).toHaveTextContent(`Current candle closes at: ${formatLocal(new Date('2024-01-10T12:35:00Z'))}`);

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(countdown).toHaveTextContent('Time remaining: 00:31');
    act(() => {
      vi.advanceTimersByTime(31000);
    });
    expect(countdown).toHaveTextContent('Time remaining: 01:00');
    expect(countdown).toHaveTextContent(`Next candle starts at: ${formatLocal(new Date('2024-01-10T12:36:00Z'))}`);
    view.unmount();
    vi.useRealTimers();
  });
});
