/**
 * Minimal, deterministic stub of the lightweight-charts v4 API surface used by TradeX.
 *
 * jsdom has no canvas, so the real library throws "Value is null" from its animation-frame
 * drawing pass (PriceAxisWidget._internal_optimalWidth). Tests exercise page logic, not pixels,
 * so the chart is replaced with a recording no-op that keeps component lifecycles intact.
 *
 * The alias is only applied while running Vitest (see vite.config.js) - the app always builds
 * against the real pinned version (4.2.3).
 */

function noop() {}

export const chartInstances = [];
export function resetChartInstances() {
  chartInstances.length = 0;
}

export const CrosshairMode = { Normal: 0, Magnet: 1, Hidden: 2 };
export const LineStyle = { Solid: 0, Dotted: 1, Dashed: 2, LargeDashed: 3, SparseDotted: 4 };
export const ColorType = { Solid: 'solid', VerticalGradient: 'gradient' };
export const PriceScaleMode = { Normal: 0, Logarithmic: 1, Percentage: 2, IndexedTo100: 3 };

function createSeries(type, chartRecord) {
  const record = { type, dataSets: [], updates: [], options: [] };
  chartRecord.series.push(record);
  return {
    setData: (data) => record.dataSets.push(data),
    update: (point) => record.updates.push(point),
    applyOptions: (options) => record.options.push(options),
    setMarkers: noop,
    setSeriesOrder: noop,
    priceScale: () => ({ applyOptions: noop }),
    createPriceLine: () => ({ applyOptions: noop }),
    removePriceLine: noop,
  };
}

export function createChart() {
  const record = { series: [], removeCalls: 0, fitCalls: 0, realTimeCalls: 0 };
  const timeScale = {
    fitContent: () => record.fitCalls++,
    applyOptions: noop,
    setVisibleLogicalRange: noop,
    getVisibleLogicalRange: () => null,
    setVisibleRange: noop,
    scrollToRealTime: () => record.realTimeCalls++,
    subscribeVisibleLogicalRangeChange: noop,
    unsubscribeVisibleLogicalRangeChange: noop,
    subscribeSizeChange: noop,
    unsubscribeSizeChange: noop,
  };
  const chart = {
    addCandlestickSeries: () => createSeries('candles', record),
    addBarSeries: () => createSeries('bars', record),
    addLineSeries: () => createSeries('line', record),
    addAreaSeries: () => createSeries('area', record),
    addBaselineSeries: () => createSeries('baseline', record),
    addHistogramSeries: () => createSeries('histogram', record),
    applyOptions: noop,
    remove: () => record.removeCalls++,
    removeSeries: noop,
    resize: noop,
    subscribeCrosshairMove: noop,
    unsubscribeCrosshairMove: noop,
    subscribeClick: noop,
    unsubscribeClick: noop,
    timeScale: () => timeScale,
    priceScale: () => ({ applyOptions: noop }),
  };
  chartInstances.push(record);
  return chart;
}

export function createSeriesMarkers() {
  return { setMarkers: noop };
}

export function createPriceLine() {
  return { applyOptions: noop };
}

export const version = '4.2.3-test-stub';
