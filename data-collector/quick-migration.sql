-- Quick Migration: Fix partition scheme with temporary limit increase
-- Run this in ClickHouse client

USE trading;

-- Step 1: Create new table with fixed partition scheme (month only)
CREATE TABLE IF NOT EXISTS candles_new
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
PARTITION BY toYYYYMM(ts)
ORDER BY (instrument_id, interval, ts)
SETTINGS index_granularity = 8192;

-- Step 2: Temporarily increase partition limit for migration
SET max_partitions_per_insert_block = 500;

-- Step 3: Copy all data
INSERT INTO candles_new
SELECT * FROM candles FINAL;

-- Step 4: Reset to default limit
SET max_partitions_per_insert_block = 100;

-- Step 5: Verify counts
SELECT 'Old table' AS name, count() AS rows FROM candles FINAL
UNION ALL
SELECT 'New table' AS name, count() AS rows FROM candles_new FINAL;

-- Step 6: If counts match, swap tables
DROP TABLE candles;
RENAME TABLE candles_new TO candles;

-- Done! New partition scheme is now active.
