package com.tradex.repository;

import com.tradex.entity.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    List<WatchlistItem> findByAccountIdOrderBySortOrderAscIdAsc(Long accountId);

    Optional<WatchlistItem> findByAccountIdAndInstrumentId(Long accountId, Long instrumentId);

    boolean existsByAccountIdAndInstrumentId(Long accountId, Long instrumentId);

    void deleteByAccountIdAndInstrumentId(Long accountId, Long instrumentId);

    void deleteByAccountId(Long accountId);

    long countByAccountId(Long accountId);
}
