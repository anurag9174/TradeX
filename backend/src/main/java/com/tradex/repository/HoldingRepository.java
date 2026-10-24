package com.tradex.repository;

import com.tradex.entity.Holding;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findByAccountIdOrderByIdAsc(Long accountId);

    Optional<Holding> findByAccountIdAndInstrumentId(Long accountId, Long instrumentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Holding h where h.accountId = :accountId and h.instrumentId = :instrumentId")
    Optional<Holding> findByAccountIdAndInstrumentIdForUpdate(@Param("accountId") Long accountId,
                                                             @Param("instrumentId") Long instrumentId);

    @Query("select coalesce(sum(h.quantity), 0) from Holding h where h.accountId = :accountId and h.instrumentId = :instrumentId")
    java.math.BigDecimal sumQuantity(@Param("accountId") Long accountId, @Param("instrumentId") Long instrumentId);

    void deleteByAccountId(Long accountId);
}
