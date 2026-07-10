package com.algo.app.candle_collector_service.controller;

import com.algo.app.candle_collector_service.service.CandleCollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/candles")
public class CandleDataController {

    private final CandleCollectionService collectionService;

    public CandleDataController(CandleCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    /**
     * Endpoint to trigger 5-year backfill for a specific security and multiple intervals
     * POST http://localhost:8080/api/v1/candles/backfill
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> triggerBackfill(
            @RequestParam String securityId,
            @RequestParam(defaultValue = "NSE_EQ") String exchangeSegment,
            @RequestParam(defaultValue = "EQUITY") String instrument,
            @RequestParam(defaultValue = "5m,15m,1D") List<String> intervals) {

        // Run the backfill asynchronously or sequentially
        // Note: For a production app, you might want to run this in a separate thread
        // using @Async so the API doesn't time out during a heavy 5-year fetch.
        try {
            for (String interval : intervals) {
                collectionService.backfill5YearsData(securityId, exchangeSegment, instrument, interval.trim());
            }

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Backfill completed successfully into ClickHouse",
                    "securityId", securityId,
                    "intervalsProcessed", intervals
            ));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "error", e.getMessage()
            ));
        }
    }

}
