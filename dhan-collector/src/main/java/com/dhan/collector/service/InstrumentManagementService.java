package com.dhan.collector.service;

import com.dhan.collector.dto.AddInstrumentRequest;
import com.dhan.collector.dto.BackfillTriggerResult;
import com.dhan.collector.dto.InstrumentStatusDto;
import com.dhan.collector.repository.InstrumentManagementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentManagementService {

    private final InstrumentManagementRepository repo;

    @Value("${dhan.collector.url:http://localhost:8080}")
    private String collectorUrl;

    // ── Read ─────────────────────────────────────────────────────────────────

    public List<InstrumentStatusDto> getAllWithStatus() {
        return repo.findAllWithStatus();
    }

    // ── Add single ───────────────────────────────────────────────────────────

    public InstrumentStatusDto addInstrument(AddInstrumentRequest req) {
        if (repo.exists(req.getSecurityId())) {
            throw new IllegalArgumentException(
                    "Instrument " + req.getSecurityId() + " already exists.");
        }
        repo.insertInstrument(req);
        log.info("Added instrument: {} ({})", req.getSymbol(), req.getSecurityId());
        return getAllWithStatus().stream()
                .filter(i -> i.getSecurityId() == req.getSecurityId())
                .findFirst()
                .orElseThrow();
    }

    // ── Add from CSV ─────────────────────────────────────────────────────────

    /**
     * Parses uploaded CSV and inserts new instruments (skips duplicates).
     * Expected CSV format (header required):
     *   security_id,symbol,exchange_segment,instrument_type
     */
    public List<AddInstrumentRequest> parseAndSaveCSV(MultipartFile file) throws Exception {
        List<AddInstrumentRequest> parsed  = new ArrayList<>();
        List<AddInstrumentRequest> toAdd   = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream()))) {

            String line;
            boolean firstLine = true;
            int lineNum = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (firstLine) { firstLine = false; continue; }
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length < 4) {
                    log.warn("CSV line {} skipped — expected 4 columns, got {}", lineNum, parts.length);
                    continue;
                }

                try {
                    AddInstrumentRequest req = new AddInstrumentRequest();
                    req.setSecurityId(Long.parseLong(parts[0].trim()));
                    req.setSymbol(parts[1].trim());
                    req.setExchangeSegment(parts[2].trim());
                    req.setInstrumentType(parts[3].trim());
                    parsed.add(req);

                    if (repo.exists(req.getSecurityId())) {
                        skipped.add(req.getSymbol());
                    } else {
                        toAdd.add(req);
                    }
                } catch (NumberFormatException e) {
                    log.warn("CSV line {} skipped — invalid security_id: {}", lineNum, parts[0]);
                }
            }
        }

        if (!toAdd.isEmpty()) {
            repo.insertInstruments(toAdd);
            log.info("CSV import: {} added, {} skipped (already exist)", toAdd.size(), skipped.size());
        }

        return toAdd;
    }

    // ── Backfill trigger ─────────────────────────────────────────────────────

    /**
     * Calls dhan-collector's /api/backfill/start endpoint to trigger backfill.
     * collector-api acts as a proxy so the Angular UI only needs to talk to one backend.
     */
    public BackfillTriggerResult triggerBackfill(List<Long> securityIds) {
        try {
            RestTemplate rest = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String url = collectorUrl + "/api/backfill/start";
            rest.postForEntity(url, new HttpEntity<>(headers), String.class);

            List<String> symbols = securityIds.isEmpty()
                    ? List.of("ALL")
                    : repo.findAllWithStatus().stream()
                            .filter(i -> securityIds.contains(i.getSecurityId()))
                            .map(InstrumentStatusDto::getSymbol)
                            .toList();

            return BackfillTriggerResult.builder()
                    .status("QUEUED")
                    .message("Backfill started in dhan-collector")
                    .symbols(symbols)
                    .build();

        } catch (Exception e) {
            log.error("Failed to call dhan-collector backfill endpoint", e);
            return BackfillTriggerResult.builder()
                    .status("ERROR")
                    .message("Could not reach dhan-collector at " + collectorUrl
                            + ". Start it first, then retry.")
                    .symbols(List.of())
                    .build();
        }
    }

    // ── Deactivate ───────────────────────────────────────────────────────────

    public void deactivate(long securityId) {
        repo.deactivateInstrument(securityId);
        log.info("Deactivated instrument {}", securityId);
    }
}
