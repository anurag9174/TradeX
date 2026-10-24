import { describe, expect, it } from 'vitest';
import {
  directionOf,
  formatMoney,
  formatPercent,
  formatQuantity,
  initialsOf,
} from '../lib/format.js';

describe('format helpers', () => {
  it('formats money with Indian grouping', () => {
    expect(formatMoney(100000)).toContain('1,00,000');
    expect(formatMoney(1234.5)).toBe('₹1,234.50');
    expect(formatMoney(1000000, 'USD')).toBe('$1,000,000.00');
  });

  it('never renders a number for missing values', () => {
    expect(formatMoney(null)).toBe('—');
    expect(formatPercent(undefined)).toBe('—');
    expect(formatQuantity(null)).toBe('—');
  });

  it('signs percentages explicitly', () => {
    expect(formatPercent(1.5)).toBe('+1.50%');
    expect(formatPercent(-0.75)).toBe('-0.75%');
    expect(formatPercent(0)).toBe('0.00%');
  });

  it('classifies direction with an explicit, colour-independent value', () => {
    expect(directionOf(5)).toBe('up');
    expect(directionOf(-5)).toBe('down');
    expect(directionOf(0)).toBe('flat');
  });

  it('builds initials for the avatar', () => {
    expect(initialsOf('Asha Rao')).toBe('AR');
    expect(initialsOf('')).toBe('');
  });
});
