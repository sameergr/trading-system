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
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CandleRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Inserts candles using a single VALUES-list INSERT statement.
     *
     * Why not jdbcTemplate.batchUpdate():
     *   batchUpdate() sends each row as a separate prepared statement execution
     *   (or at best a multi-statement batch), which the ClickHouse 0.9.1 JDBC driver
     *   translates to multiple HTTP POST requests — each one creating a new data Part.
     *   Code 252 (TOO_MANY_PARTS) fires when unmerged Parts exceed the table limit (default 300).
     *
     * This method builds one INSERT ... VALUES (row1), (row2), ... (rowN) string
     * and executes it as a single HTTP request → single Part → no throttling.
     *
     * Safe for 5000-6000 rows (typical daily backfill). For intraday chunks of
     * similar size this is also fine. SQL length stays well under ClickHouse's
     * max_query_size default of 256MB.
     */
    public int batchInsert(List<Candle> candles) {
        if (candles.isEmpty()) return 0;

        String values = candles.stream()
                .map(c -> String.format("(%d,'%s','%s','%s',%s,%s,%s,%s,%d,%d)",
                        c.getInstrumentId(),
                        c.getSymbol().replace("'", "\\'"),
                        c.getInterval(),
                        Timestamp.valueOf(c.getTs()),
                        c.getOpen(),
                        c.getHigh(),
                        c.getLow(),
                        c.getClose(),
                        c.getVolume(),
                        c.getOi()
                ))
                .collect(Collectors.joining(","));

        jdbcTemplate.execute(
                "INSERT INTO trading.candles " +
                        "(instrument_id, symbol, interval, ts, open, high, low, close, volume, oi) " +
                        "VALUES " + values
        );

        log.info("Inserted {} candles into trading.candles", candles.size());
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
