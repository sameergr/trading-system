package com.algo.app.repository;

import com.algo.app.dto.CandleDto;
import com.algo.app.dto.InstrumentDto;
import com.algo.app.model.Candle;
import com.algo.app.model.DateRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CandleRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Batch insert candles into ClickHouse.
     *
     * Deduplication layers:
     *  1. Progress table (isChunkDone) — prevents re-fetching from API entirely for done chunks.
     *  2. ReplacingMergeTree — background dedup on (instrument_id, interval, ts) sort key.
     *     Duplicates are removed during background merges; use FINAL in queries for strong reads.
     *  3. This method is safe to call multiple times with overlapping data — ReplacingMergeTree
     *     keeps the latest version based on the ts version column defined at table creation.
     */
    public int batchInsert(List<Candle> candles) {
        if (candles.isEmpty()) return 0;

        List<Object[]> batchArgs = candles.stream()
                .map(c -> new Object[]{
                        c.getInstrumentId(),
                        c.getSymbol(),
                        c.getInterval(),
                        Timestamp.valueOf(c.getTs()),
                        c.getOpen(),
                        c.getHigh(),
                        c.getLow(),
                        c.getClose(),
                        c.getVolume(),
                        c.getOi()
                })
                .toList();

        jdbcTemplate.batchUpdate("""
            INSERT INTO trading.candles
            (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, batchArgs);

        log.debug("Inserted {} candles", candles.size());
        return candles.size();
    }

    public long countCandles(long instrumentId, String interval) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM trading.candles FINAL WHERE instrument_id = ? AND interval = ?",
                Long.class,
                instrumentId, interval
        );
        return count != null ? count : 0;
    }

    public DateRange getAvailableDateRange(long instrumentId, String interval) {
        return jdbcTemplate.queryForObject("""
            SELECT
                toDate(min(ts)) AS min_date,
                toDate(max(ts)) AS max_date
            FROM trading.candles FINAL
            WHERE instrument_id = ?
              AND interval      = ?
            """,
                (rs, i) -> new DateRange(
                        rs.getDate("min_date").toLocalDate(),
                        rs.getDate("max_date").toLocalDate()
                ),
                instrumentId, interval
        );
    }

    public List<CandleDto> findCandles(long instrumentId, String interval,
                                       LocalDate from, LocalDate to) {
        log.debug("Querying candles: instrumentId={} interval={} from={} to={}",
                instrumentId, interval, from, to);

        return jdbcTemplate.query("""
            SELECT
                toUnixTimestamp(ts)  AS time,
                open,
                high,
                low,
                close,
                volume
            FROM trading.candles FINAL
            WHERE instrument_id = ?
              AND interval      = ?
              AND ts            >= toDateTime(?, 'Asia/Kolkata')
              AND ts            <= toDateTime(?, 'Asia/Kolkata')
            ORDER BY ts ASC
            """,
                (rs, i) -> CandleDto.builder()
                        .time(rs.getLong("time"))
                        .open(rs.getDouble("open"))
                        .high(rs.getDouble("high"))
                        .low(rs.getDouble("low"))
                        .close(rs.getDouble("close"))
                        .volume(rs.getLong("volume"))
                        .build(),
                instrumentId,
                interval,
                from.atStartOfDay().toString(),
                to.atTime(23, 59, 59).toString()
        );
    }

    public List<InstrumentDto> findAllInstruments() {
        return jdbcTemplate.query("""
            SELECT DISTINCT security_id, symbol, exchange_segment
            FROM trading.instruments FINAL
            WHERE active = 1
            ORDER BY symbol ASC
            """,
                (rs, i) -> InstrumentDto.builder()
                        .securityId(rs.getLong("security_id"))
                        .symbol(rs.getString("symbol"))
                        .exchangeSegment(rs.getString("exchange_segment"))
                        .build()
        );
    }
}
