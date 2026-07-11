package com.dhan.collector.repository;

import com.dhan.collector.dto.CandleDto;
import com.dhan.collector.dto.InstrumentDto;
import com.dhan.collector.model.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CandleRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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

    public long countCandles(long instrumentId, String interval) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM trading.candles FINAL WHERE instrument_id = ? AND interval = ?",
                Long.class,
                instrumentId, interval
        );
        return count != null ? count : 0;
    }

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

    /**
     * Returns the date range available in ClickHouse for a given instrument+interval.
     * Used by the frontend to set sensible default date pickers.
     */
    public record DateRange(LocalDate from, LocalDate to) {}

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


}
