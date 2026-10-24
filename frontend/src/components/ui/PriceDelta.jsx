import {
  DIRECTION_ARROW,
  DIRECTION_CLASS,
  DIRECTION_TEXT,
  directionOf,
  formatPercent,
} from '../../lib/format.js';

/**
 * A price change rendered with sign, arrow and a screen-reader description so the direction is
 * never communicated by colour alone (WCAG 1.4.1).
 */
export default function PriceDelta({ change, changePercent, className = '', showAbsolute = true }) {
  const direction = directionOf(change ?? changePercent);
  return (
    <span
      className={`num inline-flex items-baseline gap-1 ${DIRECTION_CLASS[direction]} ${className}`}
      data-direction={direction}
    >
      <span aria-hidden="true">{DIRECTION_ARROW[direction]}</span>
      <span className="sr-only">{DIRECTION_TEXT[direction]}: </span>
      {showAbsolute && change !== undefined && change !== null ? <span>{Number(change) > 0 ? '+' : ''}{Number(change).toFixed(2)}</span> : null}
      {changePercent !== undefined && changePercent !== null ? (
        <span>({formatPercent(changePercent)})</span>
      ) : null}
    </span>
  );
}
