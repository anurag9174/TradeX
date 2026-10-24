package com.tradex.repository;

import com.tradex.entity.Order;
import com.tradex.enums.OrderSide;
import com.tradex.enums.OrderStatus;
import com.tradex.enums.OrderType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderRef(String orderRef);

    Optional<Order> findByIdAndAccountId(Long id, Long accountId);

    Optional<Order> findByAccountIdAndIdempotencyKey(Long accountId, String idempotencyKey);

    /** Pessimistic lock used by the execution engine to guarantee a single fill per order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    Page<Order> findByAccountId(Long accountId, Pageable pageable);

    Page<Order> findByAccountIdAndStatus(Long accountId, OrderStatus status, Pageable pageable);

    Page<Order> findByAccountIdAndStatusIn(Long accountId, Collection<OrderStatus> statuses, Pageable pageable);

    Page<Order> findByAccountIdAndInstrumentId(Long accountId, Long instrumentId, Pageable pageable);

    Page<Order> findByAccountIdAndInstrumentIdAndStatus(Long accountId, Long instrumentId, OrderStatus status, Pageable pageable);

    @Query("select o from Order o where o.status in :statuses and o.orderType in :types order by o.createdAt asc")
    List<Order> findWorkingOrders(@Param("statuses") Collection<OrderStatus> statuses,
                                  @Param("types") Collection<OrderType> types);

    @Query("select o from Order o where o.status in :statuses order by o.createdAt asc")
    List<Order> findAllWorking(@Param("statuses") Collection<OrderStatus> statuses);

    @Query("select count(o) from Order o where o.accountId = :accountId and o.status in :statuses")
    long countByAccountAndStatusIn(@Param("accountId") Long accountId, @Param("statuses") Collection<OrderStatus> statuses);

    List<Order> findByAccountIdAndSideAndStatusAndExecutedAtGreaterThanEqual(
            Long accountId, OrderSide side, OrderStatus status, Instant from);

    List<Order> findByAccountIdAndExecutedAtGreaterThanEqual(Long accountId, Instant from);

    @Query("select count(o) from Order o where o.accountId = :accountId")
    long countByAccountId(@Param("accountId") Long accountId);

    @Query("""
            select o from Order o
            where o.accountId = :accountId
              and o.status in :statuses
              and (:instrumentId is null or o.instrumentId = :instrumentId)
              and (:side is null or o.side = :side)
              and (:from is null or o.createdAt >= :from)
              and (:to is null or o.createdAt <= :to)
            """)
    Page<Order> search(@Param("accountId") Long accountId,
                       @Param("statuses") Collection<OrderStatus> statuses,
                       @Param("instrumentId") Long instrumentId,
                       @Param("side") OrderSide side,
                       @Param("from") Instant from,
                       @Param("to") Instant to,
                       Pageable pageable);

    @Query("""
            select o from Order o
            where o.accountId = :accountId
              and o.status in :statuses
              and (:instrumentId is null or o.instrumentId = :instrumentId)
              and (:side is null or o.side = :side)
              and (:from is null or o.createdAt >= :from)
              and (:to is null or o.createdAt <= :to)
            """)
    List<Order> searchAll(@Param("accountId") Long accountId,
                          @Param("statuses") Collection<OrderStatus> statuses,
                          @Param("instrumentId") Long instrumentId,
                          @Param("side") OrderSide side,
                          @Param("from") Instant from,
                          @Param("to") Instant to);

    /**
     * Quantity already committed to resting SELL orders for one instrument.
     *
     * <p>Subtracted from the held quantity before a new sell is accepted, so the same shares can
     * never be promised to two orders.</p>
     */
    @Query("""
            select coalesce(sum(o.quantity), 0) from Order o
            where o.accountId = :accountId
              and o.instrumentId = :instrumentId
              and o.side = com.tradex.enums.OrderSide.SELL
              and o.status in :statuses
              and (:excludeOrderId is null or o.id <> :excludeOrderId)
            """)
    BigDecimal sumOpenSellQuantity(@Param("accountId") Long accountId,
                                   @Param("instrumentId") Long instrumentId,
                                   @Param("statuses") Collection<OrderStatus> statuses,
                                   @Param("excludeOrderId") Long excludeOrderId);

    void deleteByAccountId(Long accountId);
}
