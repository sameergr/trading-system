package com.algo.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DerivedCandleService {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void aggregateAll() {
        log.info("=== [Scheduled] Deriving 1W and 1M candles ===");
        aggregateNow();
    }

    public void aggregateNow() {
        // First verify there is actually 1d data to aggregate from
        Long dailyCount = jdbcTemplate.queryForObject(
            "SELECT count() FROM market_data.candles FINAL WHERE interval = '1D'",
            Long.class
        );

        log.info("1D candles available for aggregation: {}", dailyCount);

        if (dailyCount == null || dailyCount == 0) {
            log.warn("No 1D candles found — skipping 1W/1M aggregation. " +
                     "Run daily backfill first, then re-trigger derive.");
            return;
        }

        log.info("Clearing stale 1W rows...");
        deleteIntervalSync("1W");

        log.info("Clearing stale 1M rows...");
        deleteIntervalSync("1M");

        log.info("Aggregating 1W candles from {} daily rows...", dailyCount);
        long weekly = aggregateWeekly();

        log.info("Aggregating 1M candles from {} daily rows...", dailyCount);
        long monthly = aggregateMonthly();

        log.info("1W and 1M aggregation complete — 1W rows: {}, 1M rows: {}", weekly, monthly);
    }

    /**
     * Deletes interval rows synchronously by setting mutations_sync=1 for this session.
     * Without this, ALTER TABLE DELETE is async and the subsequent INSERT runs
     * before the delete finishes, causing duplicate accumulation.
     */
    private void deleteIntervalSync(String interval) {
        try {
            // Force synchronous mutation execution for this connection
            jdbcTemplate.execute("SET mutations_sync = 1");
            jdbcTemplate.execute(
                "ALTER TABLE market_data.candles DELETE WHERE interval = '" + interval + "'"
            );
            log.info("Deleted {} rows.", interval);
        } catch (Exception e) {
            log.warn("Could not delete {} rows (may not exist yet): {}", interval, e.getMessage());
        }
    }

    private long aggregateWeekly() {
        try {
            jdbcTemplate.execute("""
                INSERT INTO market_data.candles
                    (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
                SELECT
                    instrument_id,
                    any(symbol)           AS symbol,
                    '1W'                  AS interval,
                    toStartOfWeek(ts, 1)  AS week_start,
                    argMin(open,  ts)     AS open,
                    max(high)             AS high,
                    min(low)              AS low,
                    argMax(close, ts)     AS close,
                    sum(volume)           AS volume,
                    argMax(oi, ts)        AS oi
                FROM market_data.candles FINAL
                WHERE interval = '1D'
                GROUP BY instrument_id, week_start
                ORDER BY instrument_id, week_start
            """);

            Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM market_data.candles FINAL WHERE interval = '1W'",
                Long.class
            );
            log.info("1W inserted: {} rows", count);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("1W aggregation failed", e);
            return 0;
        }
    }

    private long aggregateMonthly() {
        try {
            jdbcTemplate.execute("""
                INSERT INTO market_data.candles
                    (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
                SELECT
                    instrument_id,
                    any(symbol)            AS symbol,
                    '1M'                   AS interval,
                    toStartOfMonth(ts)     AS month_start,
                    argMin(open,  ts)      AS open,
                    max(high)              AS high,
                    min(low)               AS low,
                    argMax(close, ts)      AS close,
                    sum(volume)            AS volume,
                    argMax(oi, ts)         AS oi
                FROM market_data.candles FINAL
                WHERE interval = '1D'
                GROUP BY instrument_id, month_start
                ORDER BY instrument_id, month_start
            """);

            Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM market_data.candles FINAL WHERE interval = '1M'",
                Long.class
            );
            log.info("1M inserted: {} rows", count);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("1M aggregation failed", e);
            return 0;
        }
    }
}
