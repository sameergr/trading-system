package com.dhan.collector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Derives 1W and 1M candles by aggregating daily ('1d') candles already stored in ClickHouse.
 *
 * Why aggregate instead of fetching from Dhan API directly?
 * Dhan's API does not provide weekly/monthly candles as a native interval.
 * We build them from the daily data we already have, using ClickHouse SQL aggregation.
 *
 * Aggregation rules (standard OHLCV):
 *   open   = first open of the period
 *   high   = max high of the period
 *   low    = min low of the period
 *   close  = last close of the period
 *   volume = sum of volumes in the period
 *   ts     = start of the period (Monday for week, 1st of month for monthly)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivedCandleService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs after the incremental refresh (3:45 PM IST weekdays) so daily candles
     * are already inserted before we aggregate them.
     */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void aggregateAll() {
        log.info("=== Deriving Weekly and Monthly candles ===");
        aggregateWeekly();
        aggregateMonthly();
        log.info("=== Derived candle aggregation complete ===");
    }

    /**
     * Manually trigger aggregation (e.g., after initial backfill completes).
     */
    public void aggregateNow() {
        log.info("Manual aggregation triggered.");
        aggregateWeekly();
        aggregateMonthly();
    }

    /**
     * Weekly candles: group daily candles by instrument + ISO week (Monday-aligned).
     * Inserts into the same candles table with interval = '1W'.
     * ReplacingMergeTree deduplicates on re-run.
     */
    private void aggregateWeekly() {
        log.info("Aggregating weekly candles from daily data...");
        try {
            jdbcTemplate.execute("""
                INSERT INTO trading.candles
                    (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
                SELECT
                    instrument_id,
                    any(symbol)                             AS symbol,
                    '1W'                                    AS interval,
                    toStartOfWeek(ts, 1)                    AS ts,   -- 1 = week starts Monday (ISO)
                    argMin(open,  ts)                       AS open,
                    max(high)                               AS high,
                    min(low)                                AS low,
                    argMax(close, ts)                       AS close,
                    sum(volume)                             AS volume,
                    argMax(oi, ts)                          AS oi
                FROM trading.candles FINAL
                WHERE interval = '1d'
                GROUP BY instrument_id, toStartOfWeek(ts, 1)
                ORDER BY instrument_id, ts
            """);
            log.info("Weekly candle aggregation done.");
        } catch (Exception e) {
            log.error("Weekly aggregation failed", e);
        }
    }

    /**
     * Monthly candles: group daily candles by instrument + calendar month.
     * ts = first trading day of the month.
     */
    private void aggregateMonthly() {
        log.info("Aggregating monthly candles from daily data...");
        try {
            jdbcTemplate.execute("""
                INSERT INTO trading.candles
                    (instrument_id, symbol, interval, ts, open, high, low, close, volume, oi)
                SELECT
                    instrument_id,
                    any(symbol)                             AS symbol,
                    '1M'                                    AS interval,
                    toStartOfMonth(ts)                      AS ts,
                    argMin(open,  ts)                       AS open,
                    max(high)                               AS high,
                    min(low)                                AS low,
                    argMax(close, ts)                       AS close,
                    sum(volume)                             AS volume,
                    argMax(oi, ts)                          AS oi
                FROM trading.candles FINAL
                WHERE interval = '1d'
                GROUP BY instrument_id, toStartOfMonth(ts)
                ORDER BY instrument_id, ts
            """);
            log.info("Monthly candle aggregation done.");
        } catch (Exception e) {
            log.error("Monthly aggregation failed", e);
        }
    }
}
