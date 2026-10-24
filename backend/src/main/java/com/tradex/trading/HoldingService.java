package com.tradex.trading;

import com.tradex.entity.Holding;
import com.tradex.entity.Instrument;
import com.tradex.entity.Trade;
import com.tradex.repository.HoldingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Applies executed fills to positions using <strong>average cost with capitalised buy charges</strong>.
 *
 * <h2>Accounting contract</h2>
 * <ul>
 *   <li>A buy adds quantity and re-weights the average cost as
 *       {@code (qty*avgCost + turnover + buyCharges) / newQty}. Charges therefore form part of the
 *       position's book cost, so a round trip that goes nowhere still shows a loss equal to the cost
 *       of dealing. This is deliberate: it is the honest number.</li>
 *   <li>A sell never changes {@code avgCost}; it realises
 *       {@code (fillPrice - avgCost) * qty - sellCharges} and reduces quantity.</li>
 *   <li>A position that reaches zero is deleted, so the portfolio never shows dust rows.</li>
 * </ul>
 *
 * <p>The consequence is a clean identity that the tests assert:
 * {@code portfolioValue == initialBalance + realizedPnl + unrealizedPnl}.</p>
 */
@Service
public class HoldingService {

    private final HoldingRepository holdingRepository;
    private final CurrencyConverter currencyConverter;

    public HoldingService(HoldingRepository holdingRepository, CurrencyConverter currencyConverter) {
        this.holdingRepository = holdingRepository;
        this.currencyConverter = currencyConverter;
    }

    /** Books a buy fill. Returns the updated position. */
    public Holding applyBuy(Long accountId, String accountCurrency, Instrument instrument, BigDecimal quantity, BigDecimal fillPrice,
                            BigDecimal charges, Trade trade, Instant executedAt) {
        Holding holding = holdingRepository.findByAccountIdAndInstrumentIdForUpdate(accountId, instrument.getId())
                .orElseGet(() -> newHolding(accountId, instrument.getId(), executedAt));

        // Average cost lives in the instrument's quote currency; ledger amounts (cash, charges)
        // stay in the account currency and are converted at the nominal rate.
        BigDecimal turnover = currencyConverter.toLedger(instrument, quantity.multiply(fillPrice, Decimals.MC),
                accountCurrency);
        BigDecimal previousCost = currencyConverter.toLedger(instrument,
                holding.getQuantity().multiply(holding.getAvgCost(), Decimals.MC), accountCurrency);
        BigDecimal newQuantity = Decimals.qty(holding.getQuantity().add(quantity));
        if (newQuantity.signum() <= 0) {
            throw new IllegalStateException("Buy produced a non-positive position; refusing to book it.");
        }
        BigDecimal newCost = previousCost.add(turnover).add(Decimals.nullToZero(charges));
        holding.setQuantity(newQuantity);
        holding.setAvgCost(newCost.divide(newQuantity.multiply(
                        currencyConverter.factor(instrument, accountCurrency), Decimals.MC),
                Decimals.QTY_SCALE, java.math.RoundingMode.HALF_UP));
        holding.setAccumulatedBuyCharges(Decimals.money(
                Decimals.nullToZero(holding.getAccumulatedBuyCharges()).add(Decimals.nullToZero(charges))));
        holding.setLastTradedAt(executedAt);
        return holdingRepository.save(holding);
    }

    /**
     * Books a sell fill.
     *
     * @return the realised P&amp;L, net of the sell-side charges
     */
    public BigDecimal applySell(Long accountId, String accountCurrency, Instrument instrument, BigDecimal quantity, BigDecimal fillPrice,
                                BigDecimal charges, Trade trade, Instant executedAt) {
        Holding holding = holdingRepository.findByAccountIdAndInstrumentIdForUpdate(accountId, instrument.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Sell executed without a position for account " + accountId
                                + " instrument " + instrument.getId()));

        if (holding.getQuantity().compareTo(quantity) < 0) {
            throw new IllegalStateException("Sell quantity " + quantity.toPlainString()
                    + " exceeds held quantity " + holding.getQuantity().toPlainString());
        }

        BigDecimal realized = Decimals.money(currencyConverter
                .toLedger(instrument, fillPrice.subtract(holding.getAvgCost())
                        .multiply(quantity, Decimals.MC), accountCurrency)
                .subtract(Decimals.nullToZero(charges)));

        BigDecimal remaining = Decimals.qty(holding.getQuantity().subtract(quantity));
        holding.setRealizedPnl(Decimals.money(Decimals.nullToZero(holding.getRealizedPnl()).add(realized)));
        holding.setLastTradedAt(executedAt);
        holding.setQuantity(remaining);

        if (remaining.signum() == 0) {
            holdingRepository.delete(holding);
        } else {
            holdingRepository.save(holding);
        }
        return realized;
    }

    private Holding newHolding(Long accountId, Long instrumentId, Instant openedAt) {
        Holding holding = new Holding();
        holding.setAccountId(accountId);
        holding.setInstrumentId(instrumentId);
        holding.setQuantity(BigDecimal.ZERO.setScale(Decimals.QTY_SCALE));
        holding.setAvgCost(BigDecimal.ZERO.setScale(Decimals.QTY_SCALE));
        holding.setOpenedAt(openedAt);
        return holding;
    }
}
