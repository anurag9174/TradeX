package com.tradex.repository;

import com.tradex.entity.MarketCandle;
import com.tradex.enums.MarketDataSource;
import com.tradex.enums.Timeframe;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MarketCandleRepository extends JpaRepository<MarketCandle, Long> {

    List<MarketCandle> findByInstrumentIdAndTimeframeAndOpenTimeGreaterThanEqualOrderByOpenTimeAsc(
            Long instrumentId, Timeframe timeframe, Instant from);

    List<MarketCandle> findByInstrumentIdAndTimeframeAndOpenTimeBetweenOrderByOpenTimeAsc(
            Long instrumentId, Timeframe timeframe, Instant from, Instant to);

    List<MarketCandle> findByInstrumentIdAndTimeframeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
            Long instrumentId, Timeframe timeframe, MarketDataSource source, Instant from, Instant to);

    @Query("""
            select c from MarketCandle c
            where c.instrumentId = :instrumentId
              and c.timeframe = :timeframe
              and c.source = :source
            order by c.openTime desc
            """)
    List<MarketCandle> findRecentBySourceDesc(@Param("instrumentId") Long instrumentId,
                                              @Param("timeframe") Timeframe timeframe,
                                              @Param("source") MarketDataSource source,
                                              Limit limit);

    @Query("""
            select c from MarketCandle c
            where c.instrumentId = :instrumentId
              and c.timeframe = :timeframe
              and (:from is null or c.openTime >= :from)
              and (:to is null or c.openTime <= :to)
            order by c.openTime desc
            """)
    List<MarketCandle> findRecentDesc(@Param("instrumentId") Long instrumentId,
                                      @Param("timeframe") Timeframe timeframe,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      Limit limit);

    @Query("""
            select max(c.openTime) from MarketCandle c
            where c.instrumentId = :instrumentId and c.timeframe = :timeframe and c.source = :source
            """)
    Instant findLatestOpenTimeForSource(@Param("instrumentId") Long instrumentId,
                                        @Param("timeframe") Timeframe timeframe,
                                        @Param("source") MarketDataSource source);

    @Query("select max(c.openTime) from MarketCandle c where c.instrumentId = :instrumentId and c.timeframe = :timeframe")
    Instant findLatestOpenTimeAnySource(@Param("instrumentId") Long instrumentId,
                                        @Param("timeframe") Timeframe timeframe);

    @Query("select count(c) from MarketCandle c where c.instrumentId = :instrumentId and c.timeframe = :timeframe")
    long countByKey(@Param("instrumentId") Long instrumentId, @Param("timeframe") Timeframe timeframe);

    long countBySource(MarketDataSource source);

    @Modifying
    @Query("delete from MarketCandle c where c.instrumentId = :instrumentId and c.timeframe = :timeframe and c.source = :source")
    int deleteByKeyAndSource(@Param("instrumentId") Long instrumentId,
                             @Param("timeframe") Timeframe timeframe,
                             @Param("source") MarketDataSource source);

    @Modifying
    @Query("delete from MarketCandle c where c.instrumentId = :instrumentId and c.timeframe = :timeframe and c.openTime >= :from and c.source = :source")
    int deleteFrom(@Param("instrumentId") Long instrumentId,
                   @Param("timeframe") Timeframe timeframe,
                   @Param("from") Instant from,
                   @Param("source") MarketDataSource source);
}
