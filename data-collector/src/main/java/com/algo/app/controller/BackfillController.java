package com.algo.app.controller;

import com.algo.app.repository.BackfillProgressRepository;
import com.algo.app.repository.CandleRepository;
import com.algo.app.service.BackfillService;
import com.algo.app.service.DerivedCandleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/backfill")
@RequiredArgsConstructor
public class BackfillController {

    private final BackfillService backfillService;
    private final CandleRepository candleRepository;
    private final DerivedCandleService derivedCandleService;
    private final BackfillProgressRepository progressRepository;

    /**
     * POST /api/backfill/start
     * Triggers the full historical backfill asynchronously.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startBackfill() {
        if (backfillService.isRunning()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "message", "Backfill is already in progress"
            ));
        }

        // Run in background so the HTTP response returns immediately
        CompletableFuture.runAsync(backfillService::runBackfill);

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "Backfill started in background. Check logs for progress."
        ));
    }

    /**
     * GET /api/backfill/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "running", backfillService.isRunning()
        ));
    }

    /**
     * GET /api/backfill/progress?instrumentId=11536
     * Shows resume state per interval for a given instrument.
     * Returns the last completed date and failed chunk count per interval.
     */
    @GetMapping("/progress")
    public ResponseEntity<?> getProgress(@RequestParam long instrumentId) {
        var intervals = List.of("1m", "5m", "15m", "1h", "1D", "1W", "1M");
        var result = intervals.stream().map(interval -> {
            var lastDone = progressRepository.getLastCompletedDate(instrumentId, interval);
            int failed   = progressRepository.countFailed(instrumentId, interval);
            return Map.of(
                    "interval",       interval,
                    "lastCompletedTo", lastDone.map(Object::toString).orElse("not started"),
                    "failedChunks",   failed
            );
        }).toList();
        return ResponseEntity.ok(Map.of("instrumentId", instrumentId, "progress", result));
    }
     /* Manually triggers aggregation of 1W and 1M candles from daily data.
     * Useful to run once after the initial backfill completes.
     */
    @PostMapping("/derive")
    public ResponseEntity<Map<String, String>> deriveCandles() {
        CompletableFuture.runAsync(derivedCandleService::aggregateNow);
        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "Weekly and monthly candle aggregation running in background."
        ));
    }

    /**
     * GET /api/candles/count?instrumentId=11536&interval=5m
     * Quick sanity check to verify data landed in ClickHouse.
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> countCandles(
            @RequestParam long instrumentId,
            @RequestParam String interval) {
        long count = candleRepository.countCandles(instrumentId, interval);
        return ResponseEntity.ok(Map.of(
                "instrumentId", instrumentId,
                "interval", interval,
                "count", count
        ));
    }
}
