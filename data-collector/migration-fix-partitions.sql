-- ===================================================================
-- Migration: Fix "Too many partitions per insert block" error
-- ===================================================================
-- 
-- Problem: Table partitioned by (interval, toYYYYMM(ts)) creates too
--          many partitions when inserting 85-day batches spanning 
--          multiple intervals and months.
--
-- Solution: Remove 'interval' from partition key. Partition only by
--           month (toYYYYMM(ts)). The ORDER BY key already includes
--           interval, so query performance remains optimal.
--
-- Impact: Reduces partitions from ~500 (5 intervals × 100 months)
--         to ~100 (just months), staying well under ClickHouse limits.
--
-- ===================================================================

-- Step 1: Create new table with fixed partition scheme
CREATE TABLE IF NOT EXISTS trading.candles_new
(
    instrument_id   UInt32,
    symbol          LowCardinality(String),
    interval        Enum8('1m'=1, '5m'=5, '15m'=15, '1h'=60, '1D'=100, '1W'=101, '1M'=102),
    ts              DateTime64(0, 'Asia/Kolkata'),
    open            Float64,
    high            Float64,
    low             Float64,
    close           Float64,
    volume          UInt64,
    oi              UInt64 DEFAULT 0
)
ENGINE = ReplacingMergeTree(ts)
PARTITION BY toYYYYMM(ts)            -- ← FIXED: removed 'interval' from partition key
ORDER BY (instrument_id, interval, ts)
SETTINGS index_granularity = 8192;

-- Step 2: Copy existing data month-by-month to avoid partition limit
-- This inserts one month at a time, ensuring each INSERT touches only 1 partition

-- First, check date range of existing data
SELECT 
    toYYYYMM(min(ts)) AS first_month,
    toYYYYMM(max(ts)) AS last_month,
    count() AS total_rows
FROM trading.candles;

-- Option A: Copy month-by-month manually
-- Replace YYYYMM with actual months from your data range
-- Example: for 2021-06 to 2026-07

INSERT INTO trading.candles_new
SELECT * FROM trading.candles FINAL
WHERE toYYYYMM(ts) = 202106;

-- Repeat for each month...
-- INSERT INTO trading.candles_new SELECT * FROM trading.candles FINAL WHERE toYYYYMM(ts) = 202107;
-- INSERT INTO trading.candles_new SELECT * FROM trading.candles FINAL WHERE toYYYYMM(ts) = 202108;
-- ... etc

-- Option B: Temporary increase partition limit (EASIER)
-- Set a higher limit just for this migration, then reset it

SET max_partitions_per_insert_block = 500;

INSERT INTO trading.candles_new
SELECT * FROM trading.candles FINAL;

-- Reset to default
SET max_partitions_per_insert_block = 100;

-- Step 3: Verify row counts match
SELECT 'Original table' AS source, count() AS rows FROM trading.candles FINAL
UNION ALL
SELECT 'New table' AS source, count() AS rows FROM trading.candles_new FINAL;

-- Step 4: Drop old table and rename new one
-- IMPORTANT: Only run these commands AFTER verifying counts match!
--
-- DROP TABLE trading.candles;
-- RENAME TABLE trading.candles_new TO trading.candles;

-- ===================================================================
-- Manual execution steps:
-- ===================================================================
-- 
-- Option A: Run via clickhouse-client CLI
--   docker exec -it clickhouse clickhouse-client --password admin123
--   Then paste the above SQL commands one by one
--
-- Option B: Run via HTTP API
--   curl -X POST 'http://localhost:8123/' \
--     --user admin:admin123 \
--     --data-binary @migration-fix-partitions.sql
--
-- After migration:
--   1. Verify row counts match
--   2. Uncomment and run DROP + RENAME commands
--   3. Restart your Spring Boot app
--   4. Retry backfill: curl -X POST http://localhost:8080/api/backfill/start
--
-- ===================================================================
