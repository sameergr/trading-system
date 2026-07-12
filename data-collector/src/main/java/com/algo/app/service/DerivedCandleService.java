package com.algo.app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Derives 1W and 1M candles by aggregating 1d candles already stored in ClickHouse.
 *
 * Dhan API has no native weekly/monthly endpoint — we build them here from daily data.
 *
 * Aggregation rules:
 *   open   = first open of the period   (argMin by ts)
 *   high   = max high of the period
 *   low    = min low  of the period
 *   close  = last close of the period   (argMax by ts)
 *   volume = sum of daily volumes
 *   ts     = Monday of the week / 1st of the month (ClickHouse toStartOfWeek / toStartOfMonth)
 *
 * Called automatically:
 *   - At end of every BackfillService.runBackfill()
 *   - Scheduled at 3:45 PM IST weekdays (15 min after IncrementalRefreshService)
 *   - Manually via POST /api/backfill/derive
 */
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

    /**
     * Full re-aggregation:
     *  1. Delete existing 1W and 1M rows (instant in ClickHouse via ALTER DELETE)
     *  2. Re-insert fresh aggregations from current 1d data
     *
     * Deletion before insert is important because ReplacingMergeTree deduplication
     * runs in the background — without deletion, duplicate rows accumulate until
     * ClickHouse decides to merge, causing inflated candle counts in queries.
     */
    public void aggregateNow() {
        log.info("Deleting stale 1W rows...");
        deleteInterval("1W");

        log.info("Deleting stale 1M rows...");
        deleteInterval("1M");

        log.info("Aggregating 1W candles from 1d data...");
        aggregateWeekly();

        log.info("Aggregating 1M candles from 1d data...");
        aggregateMonthly();

        log.info("1W and 1M aggregation complete.");
    }

    private void deleteInterval(String interval) {
        try {
            // ALTER TABLE DELETE is synchronous in ClickHouse when mutations_sync=1
            jdbcTemplate.execute(
                "ALTER TABLE trading.candles DELETE WHERE interval = '" + interval + "'"
            );
            log.info("Deleted existing {} rows.", interval);
        } catch (Exception e) {
            log.warn("Could not delete {} rows (table may be empty): {}", interval, e.getMessage());
        }
    }

    private void aggregateWeekly() {
        try {
            jdbcTemplate.execute("""
                INSERT INTO trading.candles
                    (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
                SELECT
                    instrument_id,
                    any(symbol)              AS symbol,
                    '1W'                     AS interval,
                    toStartOfWeek(ts, 1)     AS ts,
                    argMin(open,  ts)        AS open,
                    max(high)                AS high,
                    min(low)                 AS low,
                    argMax(close, ts)        AS close,
                    sum(volume)              AS volume,
                    argMax(oi, ts)           AS oi
                FROM trading.candles FINAL
                WHERE interval = '1d'
                  AND ts IS NOT NULL
                GROUP BY instrument_id, toStartOfWeek(ts, 1)
                ORDER BY instrument_id, ts
            """);
            log.info("1W aggregation inserted.");
        } catch (Exception e) {
            log.error("1W aggregation failed", e);
        }
    }

    private void aggregateMonthly() {
        try {
            jdbcTemplate.execute("""
                INSERT INTO trading.candles
                    (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
                SELECT
                    instrument_id,
                    any(symbol)              AS symbol,
                    '1M'                     AS interval,
                    toStartOfMonth(ts)       AS ts,
                    argMin(open,  ts)        AS open,
                    max(high)                AS high,
                    min(low)                 AS low,
                    argMax(close, ts)        AS close,
                    sum(volume)              AS volume,
                    argMax(oi, ts)           AS oi
                FROM trading.candles FINAL
                WHERE interval = '1d'
                  AND ts IS NOT NULL
                GROUP BY instrument_id, toStartOfMonth(ts)
                ORDER BY instrument_id, ts
            """);
            log.info("1M aggregation inserted.");
        } catch (Exception e) {
            log.error("1M aggregation failed", e);
        }
    }
}
