package com.tradex.repository;

import com.tradex.entity.TradingAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TradingAccountRepository extends JpaRepository<TradingAccount, Long> {

    Optional<TradingAccount> findByUserId(Long userId);

    @Query("select a from TradingAccount a where a.userId = :userId")
    Optional<TradingAccount> findByUserIdReadOnly(@Param("userId") Long userId);

    boolean existsByUserId(Long userId);

    /**
     * Takes a row-level write lock ({@code SELECT ... FOR UPDATE}) on the account.
     *
     * <p>This is the serialisation point for every balance mutation. Two concurrent buy orders
     * against the same account are forced to run one after the other, so a stale balance can
     * never be spent twice.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TradingAccount a where a.id = :id")
    Optional<TradingAccount> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TradingAccount a where a.userId = :userId")
    Optional<TradingAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
