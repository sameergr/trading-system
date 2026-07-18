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
        // First verify there is actually 1D data to aggregate from
        Long dailyCount = jdbcTemplate.queryForObject(
            "SELECT count() FROM trading.candles WHERE interval = '1D'",
            Long.class
        );

        log.info("1D candles available for aggregation: {}", dailyCount);

        if (dailyCount == null || dailyCount == 0) {
            log.warn("No 1D candles found — skipping 1W/1M aggregation. " +
                     "Run daily backfill first, then re-trigger derive.");
            return;
        }

        // IMPORTANT: Dhan's /charts/historical returns HOURLY candles, not daily!
        // Multiple candles per day need to be aggregated into one true daily candle first.
        log.info("Note: '1D' interval contains hourly candles from Dhan API, aggregating to true daily first...");

        log.info("Clearing stale 1W rows...");
        deleteIntervalSync("1W");

        log.info("Clearing stale 1M rows...");
        deleteIntervalSync("1M");

        log.info("Aggregating 1W candles from {} hourly '1D' rows...", dailyCount);
        long weekly = aggregateWeekly();

        log.info("Aggregating 1M candles from {} hourly '1D' rows...", dailyCount);
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
                "ALTER TABLE trading.candles DELETE WHERE interval = '" + interval + "'"
            );
            log.info("Deleted {} rows.", interval);
        } catch (Exception e) {
            log.warn("Could not delete {} rows (may not exist yet): {}", interval, e.getMessage());
        }
    }

    private long aggregateWeekly() {
        try {
            jdbcTemplate.execute("SET max_partitions_per_insert_block = 500");
            
            jdbcTemplate.execute("""
                INSERT INTO trading.candles
                SELECT
                    instrument_id,
                    any(symbol) AS symbol,
                    '1W' AS interval,
                    week_start AS ts,
                    argMin(open, orig_ts) AS open,
                    max(high) AS high,
                    min(low) AS low,
                    argMax(close, orig_ts) AS close,
                    sum(volume) AS volume,
                    argMax(oi, orig_ts) AS oi
                FROM (
                    SELECT 
                        instrument_id,
                        symbol,
                        ts AS orig_ts,
                        toMonday(toDate(ts)) AS week_start,
                        open,
                        high,
                        low,
                        close,
                        volume,
                        oi
                    FROM trading.candles
                    WHERE interval = '1D'
                )
                GROUP BY instrument_id, week_start
            """);

            Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM trading.candles WHERE interval = '1W'",
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
            // Dhan's /charts/historical returns multiple hourly candles per day labeled as '1D'
            // We need to: 1) Aggregate to true daily bars, 2) Then aggregate to monthly
            jdbcTemplate.execute("""
                INSERT INTO trading.candles
                    (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
                SELECT
                    instrument_id,
                    any(symbol)                       AS symbol,
                    '1M'                              AS interval,
                    toStartOfMonth(toDate(ts))        AS month_start,
                    argMin(open, ts)                  AS open,
                    max(high)                         AS high,
                    min(low)                          AS low,
                    argMax(close, ts)                 AS close,
                    sum(volume)                       AS volume,
                    argMax(oi, ts)                    AS oi
                FROM trading.candles
                WHERE interval = '1D'
                GROUP BY instrument_id, month_start
            """);

            Long count = jdbcTemplate.queryForObject(
                "SELECT count() FROM trading.candles WHERE interval = '1M'",
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
