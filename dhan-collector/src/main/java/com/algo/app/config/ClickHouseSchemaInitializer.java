package com.dhan.collector.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClickHouseSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing ClickHouse schema...");
        createDatabase();
        createCandlesTable();
        migrateIntervalEnum();
        createInstrumentsTable();
        createBackfillProgressTable();
        log.info("ClickHouse schema ready.");
    }

    private void createDatabase() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS trading");
    }

    private void createCandlesTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS trading.candles
            (
                instrument_id   UInt32,
                symbol          LowCardinality(String),
                interval        Enum8('1m'=1, '5m'=5, '15m'=15, '60m'=60, '1d'=100, '1W'=101, '1M'=102),
                ts              DateTime64(0, 'Asia/Kolkata'),
                open            Float64,
                high            Float64,
                low             Float64,
                close           Float64,
                volume          UInt64,
                oi              UInt64 DEFAULT 0
            )
            ENGINE = ReplacingMergeTree(ts)
            PARTITION BY (interval, toYYYYMM(ts))
            ORDER BY (instrument_id, interval, ts)
            SETTINGS index_granularity = 8192
        """);
        log.info("Table trading.candles ready.");
    }

    /**
     * If the table already exists with the old Enum8 definition, alter it to add new values.
     * Safe to run on every startup — ClickHouse ALTER ENUM is idempotent.
     */
    private void migrateIntervalEnum() {
        try {
            jdbcTemplate.execute("""
                ALTER TABLE trading.candles
                MODIFY COLUMN interval
                Enum8('1m'=1, '5m'=5, '15m'=15, '60m'=60, '1d'=100, '1W'=101, '1M'=102)
            """);
            log.info("Enum migration for interval column applied.");
        } catch (Exception e) {
            log.warn("Enum migration skipped (table may not exist yet or already up to date): {}", e.getMessage());
        }
    }

    private void createInstrumentsTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS trading.instruments
            (
                security_id         UInt32,
                symbol              String,
                exchange_segment    String,
                instrument_type     String,
                active              UInt8 DEFAULT 1,
                created_at          DateTime DEFAULT now()
            )
            ENGINE = ReplacingMergeTree(created_at)
            ORDER BY (security_id)
        """);
        log.info("Table trading.instruments ready.");
    }

    private void createBackfillProgressTable() {
        // Tracks progress per instrument+interval so backfill can resume after restart
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS trading.backfill_progress
            (
                instrument_id   UInt32,
                interval        String,
                from_date       Date,
                to_date         Date,
                status          Enum8('PENDING'=0, 'IN_PROGRESS'=1, 'DONE'=2, 'FAILED'=3),
                rows_inserted   UInt32 DEFAULT 0,
                updated_at      DateTime DEFAULT now()
            )
            ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (instrument_id, interval, from_date)
        """);
        log.info("Table trading.backfill_progress ready.");
    }
}
