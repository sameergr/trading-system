package com.algo.app.candle_collector_service.model;

import java.time.LocalDateTime;

public record Candle(String instrumentId,
                     String interval,
                     LocalDateTime timestamp,
                     double open,
                     double high,
                     double low,
                     double close,
                     long volume,
                     long oi) {}
