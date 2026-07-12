package com.algo.app.controller;

import com.algo.app.dto.AddInstrumentRequest;
import com.algo.app.dto.BackfillTriggerResult;
import com.algo.app.dto.InstrumentStatusDto;
import com.algo.app.service.InstrumentManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentManagementController {

    private final InstrumentManagementService service;

    /**
     * GET /api/instruments/status
     * All instruments with per-interval backfill status.
     */
    @GetMapping("/status")
    public ResponseEntity<List<InstrumentStatusDto>> getAllStatus() {
        return ResponseEntity.ok(service.getAllWithStatus());
    }

    /**
     * POST /api/instruments
     * Add a single instrument from the UI form.
     * Body: { securityId, symbol, exchangeSegment, instrumentType }
     */
    @PostMapping
    public ResponseEntity<?> addInstrument(@RequestBody AddInstrumentRequest req) {
        try {
            InstrumentStatusDto result = service.addInstrument(req);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/instruments/csv
     * Upload a CSV file to bulk-add instruments.
     * CSV format: security_id,symbol,exchange_segment,instrument_type
     */
    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadCSV(@RequestParam("file") MultipartFile file) {
        try {
            List<AddInstrumentRequest> added = service.parseAndSaveCSV(file);
            return ResponseEntity.ok(Map.of(
                    "added", added.size(),
                    "symbols", added.stream().map(AddInstrumentRequest::getSymbol).toList()
            ));
        } catch (Exception e) {
            log.error("CSV upload failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "CSV parse failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/instruments/backfill
     * Trigger backfill for specific instruments or all if body is empty.
     * Body: { securityIds: [11536, 1333] } or {} for all
     */
    @PostMapping("/backfill")
    public ResponseEntity<BackfillTriggerResult> triggerBackfill(
            @RequestBody(required = false) Map<String, List<Long>> body) {
        List<Long> ids = (body != null && body.containsKey("securityIds"))
                ? body.get("securityIds")
                : List.of();
        return ResponseEntity.ok(service.triggerBackfill(ids));
    }

    /**
     * DELETE /api/instruments/{securityId}
     * Deactivate an instrument (marks active=0, candle data is retained).
     */
    @DeleteMapping("/{securityId}")
    public ResponseEntity<Map<String, String>> deactivate(@PathVariable long securityId) {
        service.deactivate(securityId);
        return ResponseEntity.ok(Map.of("status", "deactivated", "securityId", String.valueOf(securityId)));
    }
}