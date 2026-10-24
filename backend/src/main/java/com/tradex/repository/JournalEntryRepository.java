package com.tradex.repository;

import com.tradex.entity.JournalEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Page<JournalEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    void deleteByAccountId(Long accountId);
}
