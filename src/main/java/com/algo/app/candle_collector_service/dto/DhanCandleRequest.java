package com.algo.app.candle_collector_service.dto;

public record DhanCandleRequest(
        String securityId,
        String exchangeSegment,
        String instrument,
        String interval,
        boolean oi,
        String fromDate,
        String toDate) {
}
