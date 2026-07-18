-- ===================================================================
-- Migration: Convert from ReplacingMergeTree to MergeTree
-- ===================================================================
--
-- Reason: ReplacingMergeTree causes INSERT-SELECT with GROUP BY to
--         silently fail (written_rows: 0) in ClickHouse 26.6.1
--
-- Fix: Use simple MergeTree engine which works reliably with
--      INSERT-SELECT aggregations
--
-- ===================================================================

-- Step 1: Rename existing table
RENAME TABLE trading.candles TO trading.candles_old;

-- Step 2: Create new table with MergeTree engine
CREATE TABLE trading.candles
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
ENGINE = MergeTree()
PARTITION BY toYYYYMM(ts)
ORDER BY (instrument_id, interval, ts)
SETTINGS index_granularity = 8192;

-- Step 3: Copy data (FINAL not needed since it's the last read from old table)
INSERT INTO trading.candles
SELECT * FROM trading.candles_old FINAL;

-- Step 4: Verify counts match
SELECT 'Old table' AS name, count() AS rows FROM trading.candles_old FINAL
UNION ALL
SELECT 'New table' AS name, count() AS rows FROM trading.candles;

-- Step 5: If counts match, drop old table
-- DROP TABLE trading.candles_old;

-- ===================================================================
-- After migration, aggregate weekly/monthly data:
-- ===================================================================

-- Weekly aggregation
INSERT INTO trading.candles
SELECT
    instrument_id,
    any(symbol) AS symbol,
    '1W' AS interval,
    toMonday(toDate(ts)) AS ts,
    argMin(open, ts) AS open,
    max(high) AS high,
    min(low) AS low,
    argMax(close, ts) AS close,
    sum(volume) AS volume,
    argMax(oi, ts) AS oi
FROM trading.candles
WHERE interval = '1D'
GROUP BY instrument_id, toMonday(toDate(ts));

-- Monthly aggregation
INSERT INTO trading.candles
SELECT
    instrument_id,
    any(symbol) AS symbol,
    '1M' AS interval,
    toStartOfMonth(toDate(ts)) AS ts,
    argMin(open, ts) AS open,
    max(high) AS high,
    min(low) AS low,
    argMax(close, ts) AS close,
    sum(volume) AS volume,
    argMax(oi, ts) AS oi
FROM trading.candles
WHERE interval = '1D'
GROUP BY instrument_id, toStartOfMonth(toDate(ts));

-- Final verification
SELECT interval, count() AS candles
FROM trading.candles
GROUP BY interval
ORDER BY interval;

-- ===================================================================
-- Manual execution:
-- ===================================================================
-- docker exec -it clickhouse clickhouse-client --password admin123
-- Then paste the SQL above step by step
-- ===================================================================
