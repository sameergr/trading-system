# DhanCharts — Angular + TradingView Lightweight Charts

Full-stack market chart viewer. Reads OHLCV candle data from ClickHouse
(populated by dhan-collector) and renders it using TradingView's open-source
Lightweight Charts library.

---

## Architecture

```
ClickHouse (market_data.candles)
        │
        ▼
chart-api (Spring Boot :8080)     ← serves candles as JSON
        │
        ▼
dhan-charts (Angular :4200)       ← renders with Lightweight Charts
```

---

## Prerequisites

- Node.js 20+, npm
- chart-api Spring Boot service running (see chart-api/README)
- ClickHouse running with data from dhan-collector

---

## Run

```bash
npm install
npm start          # http://localhost:4200
```

---

## Features

- **Symbol selector** — all instruments from ClickHouse instruments table
- **Interval pills** — 1m / 5m / 15m / 1h / 1d / 1W / 1M
- **Date range picker** — from/to with per-interval sensible defaults
- **Candlestick chart** — TradingView Lightweight Charts (same library as tradingview.com)
- **Volume histogram** — colour-coded green/red, overlaid at bottom 20%
- **OHLCV tooltip strip** — live values on crosshair hover, last candle when idle
- **Change %** — colour-coded open→close percentage per bar
- **Loading / error states** — animated overlay

---

## What TradingView Lightweight Charts provides

- Sub-millisecond canvas rendering (WebGL-accelerated)
- Built-in crosshair, price scale, time scale with zoom/pan
- Candlestick, Histogram, Line, Area series types
- The exact same look/feel as tradingview.com charts
- MIT licensed, free to use

---

## Adding more instruments

Add rows to `instruments.csv` in dhan-collector, re-trigger backfill, and
the Angular symbol selector will pick them up automatically on next load.

---

## Production build

```bash
npm run build:prod
# Output: dist/dhan-charts/  — serve via nginx
```

### nginx config snippet for production

```nginx
server {
    listen 80;
    root /var/www/dhan-charts;
    index index.html;

    # Angular routing
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Proxy chart-api to avoid CORS in production
    location /api/ {
        proxy_pass http://localhost:8080/api/;
    }
}
```
