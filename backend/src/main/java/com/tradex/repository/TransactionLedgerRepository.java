package com.tradex.repository;

import com.tradex.entity.TransactionLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionLedgerRepository extends JpaRepository<TransactionLedger, Long> {

    Page<TransactionLedger> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    List<TransactionLedger> findByAccountIdOrderByCreatedAtAsc(Long accountId);

    Optional<TransactionLedger> findFirstByAccountIdOrderByCreatedAtDescIdDesc(Long accountId);

    @Query("select count(l) from TransactionLedger l where l.accountId = :accountId and l.entryType = com.tradex.enums.LedgerEntryType.INITIAL_GRANT")
    long countInitialGrants(@Param("accountId") Long accountId);

    void deleteByAccountId(Long accountId);
}
