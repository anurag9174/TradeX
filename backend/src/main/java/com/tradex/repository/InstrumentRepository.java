package com.tradex.repository;

import com.tradex.entity.Instrument;
import com.tradex.enums.AssetClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findBySymbolIgnoreCase(String symbol);

    List<Instrument> findByActiveTrueOrderBySortOrderAscSymbolAsc();

    List<Instrument> findByActiveTrueAndAssetClassOrderBySortOrderAscSymbolAsc(AssetClass assetClass);

    List<Instrument> findBySymbolIn(List<String> symbols);
}
