package com.algo.app.controller;

import com.algo.app.dto.CandleDto;
import com.algo.app.dto.InstrumentDto;
import com.algo.app.model.DateRange;
import com.algo.app.service.CandleService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChartController {

    private final CandleService candleService;

    /**
     * GET /api/instruments
     * Returns all active instruments for the symbol selector.
     */
    @GetMapping("/instruments")
    public ResponseEntity<List<InstrumentDto>> getInstruments() {
        return ResponseEntity.ok(candleService.getInstruments());
    }

    /**
     * GET /api/candles?instrumentId=11536&interval=5m&from=2024-01-01&to=2024-12-31
     * Returns OHLCV candles in Lightweight collectors format (unix timestamp).
     */
    @GetMapping("/candles")
    public ResponseEntity<List<CandleDto>> getCandles(
            @RequestParam long instrumentId,
            @RequestParam String interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<CandleDto> candles = candleService.getCandles(instrumentId, interval, from, to);
        return ResponseEntity.ok(candles);
    }

    /**
     * GET /api/candles/range?instrumentId=11536&interval=5m
     * Returns the min/max date available in ClickHouse for a given instrument+interval.
     * Frontend uses this to set default date range pickers.
     */
    @GetMapping("/candles/range")
    public ResponseEntity<Map<String, String>> getDateRange(
            @RequestParam long instrumentId,
            @RequestParam String interval) {

        DateRange range = candleService.getDateRange(instrumentId, interval);
        return ResponseEntity.ok(Map.of(
                "from", range.from().toString(),
                "to",   range.to().toString()
        ));
    }
}
