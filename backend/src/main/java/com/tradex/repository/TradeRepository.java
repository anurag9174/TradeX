package com.tradex.repository;

import com.tradex.entity.Trade;
import com.tradex.enums.OrderSide;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    Optional<Trade> findByTradeRef(String tradeRef);

    Optional<Trade> findByTradeRefAndAccountId(String tradeRef, Long accountId);

    Optional<Trade> findByExecutionKey(String executionKey);

    Page<Trade> findByAccountId(Long accountId, Pageable pageable);

    List<Trade> findByAccountIdOrderByExecutedAtDesc(Long accountId);

    List<Trade> findByAccountIdAndExecutedAtGreaterThanEqualOrderByExecutedAtDesc(Long accountId, Instant from);

    List<Trade> findByOrderId(Long orderId);

    @Query("""
            select t from Trade t
            where t.accountId = :accountId
              and (:instrumentId is null or t.instrumentId = :instrumentId)
              and (:side is null or t.side = :side)
              and (:from is null or t.executedAt >= :from)
              and (:to is null or t.executedAt <= :to)
            order by t.executedAt desc
            """)
    Page<Trade> search(@Param("accountId") Long accountId,
                       @Param("instrumentId") Long instrumentId,
                       @Param("side") OrderSide side,
                       @Param("from") Instant from,
                       @Param("to") Instant to,
                       Pageable pageable);

    @Query("""
            select t from Trade t
            where t.accountId = :accountId
              and (:instrumentId is null or t.instrumentId = :instrumentId)
              and (:side is null or t.side = :side)
              and (:from is null or t.executedAt >= :from)
              and (:to is null or t.executedAt <= :to)
            order by t.executedAt desc
            """)
    List<Trade> searchAll(@Param("accountId") Long accountId,
                          @Param("instrumentId") Long instrumentId,
                          @Param("side") OrderSide side,
                          @Param("from") Instant from,
                          @Param("to") Instant to);

    @Query("select coalesce(sum(t.realizedPnl), 0) from Trade t where t.accountId = :accountId")
    BigDecimal sumRealizedPnl(@Param("accountId") Long accountId);

    @Query("select coalesce(sum(t.realizedPnl), 0) from Trade t where t.accountId = :accountId and t.executedAt >= :from")
    BigDecimal sumRealizedPnlSince(@Param("accountId") Long accountId, @Param("from") Instant from);

    @Query("select coalesce(sum(t.charges), 0) from Trade t where t.accountId = :accountId and t.executedAt >= :from")
    BigDecimal sumChargesSince(@Param("accountId") Long accountId, @Param("from") Instant from);

    @Query("select count(t) from Trade t where t.accountId = :accountId and t.executedAt >= :from")
    long countSince(@Param("accountId") Long accountId, @Param("from") Instant from);

    void deleteByAccountId(Long accountId);
}
