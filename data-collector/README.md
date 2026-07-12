# Dhan Historical Data Collector

Spring Boot service to backfill and incrementally collect OHLCV candle data
from the Dhan API into ClickHouse — for all instruments and intervals.

---

## Architecture

```
Dhan API (v2)
    │
    ▼
DhanApiClient       ← Rate-limited WebClient (Resilience4j)
    │
    ▼
BackfillService     ← Chunks 5 years into 85-day windows per instrument × interval
    │
    ▼
CandleMapper        ← Converts Dhan parallel arrays → Candle objects
    │
    ▼
CandleRepository    ← Batch inserts into ClickHouse (5000 rows/batch)
    │
    ▼
ClickHouse          ← ReplacingMergeTree, partitioned by (interval, month)
```

---

## Prerequisites

- Java 21
- Maven 3.9+
- ClickHouse running on localhost:8123
- Dhan trading account with API access

---

## Setup

### 1. ClickHouse
```bash
# Start ClickHouse (Docker)
docker run -d --name clickhouse \
  -p 8123:8123 -p 9000:9000 \
  clickhouse/clickhouse-server
```

### 2. Configure instruments
Edit `src/main/resources/instruments.csv` to add the stocks you want:
```
security_id,symbol,exchange_segment,instrument_type
11536,SBIN,NSE_EQ,EQUITY
1333,HDFCBANK,NSE_EQ,EQUITY
```

> Get security IDs from Dhan's instrument master:
> https://images.dhan.co/api-data/api-scrip-master.csv

### 3. Set your Dhan API token
```bash
export DHAN_ACCESS_TOKEN=your_token_here
```
Generate a token from your Dhan app → API section.
Tokens expire in 24 hours — you'll need to refresh daily for incremental jobs.

### 4. Build and run
```bash
mvn clean package -DskipTests
java -jar target/dhan-collector-1.0.0.jar
```

---

## Usage

### Trigger backfill (one-time, resumes on restart)
```bash
curl -X POST http://localhost:8080/api/backfill/start
```

### Check if backfill is running
```bash
curl http://localhost:8080/api/backfill/status
```

### Verify data in ClickHouse
```bash
curl "http://localhost:8080/api/backfill/count?instrumentId=11536&interval=5m"
```

Or directly in ClickHouse:
```sql
SELECT count(), min(ts), max(ts)
FROM trading.candles FINAL
WHERE instrument_id = 11536 AND interval = '5m';
```

---

## What gets collected

| Interval | Dhan API endpoint       | Years of history |
|----------|-------------------------|-----------------|
| 1m       | /v2/charts/intraday     | 5 years         |
| 5m       | /v2/charts/intraday     | 5 years         |
| 15m      | /v2/charts/intraday     | 5 years         |
| 60m      | /v2/charts/intraday     | 5 years         |
| 1d       | /v2/charts/historical   | Since inception |

Chunking: intraday calls are split into 85-day windows (safely below 90-day API limit).

---

## Incremental refresh

After market close (3:30 PM IST, Mon-Fri), the service automatically fetches
today's candles for all instruments and intervals. Controlled by:
```yaml
scheduler:
  incremental-cron: "0 30 15 * * MON-FRI"
```

---

## Scale estimates

| Instruments | Intervals | Years | Approx rows   |
|-------------|-----------|-------|---------------|
| 10          | 4 intraday + 1d | 5 | ~10M       |
| 100         | 4 intraday + 1d | 5 | ~100M      |
| 500         | 4 intraday + 1d | 5 | ~500M      |

ClickHouse handles 500M+ rows comfortably with the partition + sort key setup.

---

## ClickHouse table schema

```sql
CREATE TABLE trading.candles (
    instrument_id   UInt32,
    symbol          LowCardinality(String),
    interval        Enum8('1m'=1, '5m'=5, '15m'=15, '60m'=60, '1d'=100),
    ts              DateTime64(0, 'Asia/Kolkata'),
    open            Float64,
    high            Float64,
    low             Float64,
    close           Float64,
    volume          UInt64,
    oi              UInt64
)
ENGINE = ReplacingMergeTree(ts)
PARTITION BY (interval, toYYYYMM(ts))
ORDER BY (instrument_id, interval, ts);
```

---

## Next steps
- Add a Python/FastAPI analytics service to run backtrader strategies on this data
- Build Angular + TradingView Lightweight Charts UI to visualize candles + signals
- Add more instruments to instruments.csv and re-trigger backfill
