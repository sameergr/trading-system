package com.algo.app.service;

import com.algo.app.client.DhanApiClient;
import com.algo.app.config.DhanProperties;
import com.algo.app.dto.DhanCandleResponse;
import com.algo.app.model.Candle;
import com.algo.app.model.CandleInterval;
import com.algo.app.model.Instrument;
import com.algo.app.repository.BackfillProgressRepository;
import com.algo.app.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackfillService {

    private final DhanApiClient dhanApiClient;
    private final CandleMapper candleMapper;
    private final CandleRepository candleRepository;
    private final BackfillProgressRepository progressRepository;
    private final InstrumentService instrumentService;
    private final DhanProperties properties;

    // Injected via setter to avoid circular dependency (DerivedCandleService → BackfillService loop)
    private DerivedCandleService derivedCandleService;

    public void setDerivedCandleService(DerivedCandleService derivedCandleService) {
        this.derivedCandleService = derivedCandleService;
    }

    private volatile boolean running = false;

    /**
     * Full backfill flow:
     *  1. Fetch intraday candles (1m, 5m, 15m, 60m) — chunked 85-day windows per instrument
     *  2. Fetch daily candles (1d) — single call per instrument, full history
     *  3. Derive weekly (1W) and monthly (1M) — ClickHouse aggregation from 1d data
     *
     * Resume: skips already-DONE chunks using the backfill_progress table.
     * Dedup:  ReplacingMergeTree handles duplicate rows silently.
     */
    public void runBackfill() {
        if (running) {
            log.warn("Backfill already in progress, skipping trigger.");
            return;
        }

        running = true;
        log.info("╔══════════════════════════════════════╗");
        log.info("║        BACKFILL STARTING             ║");
        log.info("╚══════════════════════════════════════╝");

        AtomicInteger totalInserted = new AtomicInteger(0);
        AtomicInteger totalSkipped  = new AtomicInteger(0);
        AtomicInteger totalFailed   = new AtomicInteger(0);

        List<Instrument> instruments = instrumentService.getAllInstruments();

        try {
            for (Instrument instrument : instruments) {

                // Step 1: Intraday intervals (1m, 5m, 15m, 60m)
                for (int intervalMinutes : properties.getBackfill().getIntervals()) {
                    CandleInterval interval;
                    try {
                        interval = CandleInterval.fromMinutes(intervalMinutes);
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown interval {}min in config — skipping", intervalMinutes);
                        continue;
                    }

                    if (!interval.isFetchable()) {
                        log.debug("Skipping derived interval {} — will be aggregated after daily fetch",
                                interval.getClickhouseValue());
                        continue;
                    }

                    BackfillResult result = backfillIntraday(instrument, interval);
                    totalInserted.addAndGet(result.inserted());
                    totalSkipped.addAndGet(result.skipped());
                    totalFailed.addAndGet(result.failed());
                }

                // Step 2: Daily candles (1d) — separate endpoint, no chunk restriction
                BackfillResult dailyResult = backfillDaily(instrument);
                totalInserted.addAndGet(dailyResult.inserted());
                totalSkipped.addAndGet(dailyResult.skipped());
                totalFailed.addAndGet(dailyResult.failed());
            }

            // Step 3: Derive 1W and 1M from the 1d data we just inserted
            // This must run AFTER all daily candles are inserted across all instruments
            log.info("▶ Deriving 1W and 1M candles from daily data...");
            if (derivedCandleService != null) {
                derivedCandleService.aggregateNow();
            } else {
                log.warn("DerivedCandleService not wired — skipping 1W/1M aggregation. " +
                         "Call POST /api/backfill/derive manually.");
            }

        } finally {
            running = false;
        }

        log.info("╔══════════════════════════════════════╗");
        log.info("║        BACKFILL COMPLETE             ║");
        log.info("║  Inserted : {}",        totalInserted.get());
        log.info("║  Skipped  : {} (done)", totalSkipped.get());
        log.info("║  Failed   : {} chunks", totalFailed.get());
        log.info("╚══════════════════════════════════════╝");
    }

    // ── Intraday (1m, 5m, 15m, 60m) ──────────────────────────────────────────

    private BackfillResult backfillIntraday(Instrument instrument, CandleInterval interval) {
        String intervalKey = interval.getClickhouseValue();

        progressRepository.resetStaleInProgress(instrument.getSecurityId(), intervalKey);

        LocalDate globalEnd   = LocalDate.now();
        LocalDate globalStart = globalEnd.minusYears(properties.getBackfill().getYearsBack());

        Optional<LocalDate> lastDone = progressRepository.getLastCompletedDate(
                instrument.getSecurityId(), intervalKey);

        LocalDate resumeFrom = lastDone
                .map(d -> d.plusDays(1))
                .orElse(globalStart);

        if (!resumeFrom.isBefore(globalEnd)) {
            log.info("✓ {}/{} already fully backfilled up to {}",
                    instrument.getSymbol(), intervalKey, globalEnd);
            return new BackfillResult(0, 1, 0);
        }

        log.info("▶ Backfilling {}/{} | {} → {} (resuming from {})",
                instrument.getSymbol(), intervalKey, globalStart, globalEnd, resumeFrom);

        int chunkDays = properties.getBackfill().getChunkDays();
        List<Candle> buffer = new ArrayList<>();
        int inserted = 0, skipped = 0, failed = 0;
        LocalDate chunkStart = resumeFrom;

        while (chunkStart.isBefore(globalEnd)) {
            LocalDate chunkEnd = chunkStart.plusDays(chunkDays - 1);
            if (chunkEnd.isAfter(globalEnd)) chunkEnd = globalEnd;

            if (progressRepository.isChunkDone(
                    instrument.getSecurityId(), intervalKey, chunkStart, chunkEnd)) {
                log.debug("  skip (already done): {} → {}", chunkStart, chunkEnd);
                skipped++;
                chunkStart = chunkEnd.plusDays(1);
                continue;
            }

            try {
                progressRepository.markInProgress(
                        instrument.getSecurityId(), intervalKey, chunkStart, chunkEnd);

                DhanCandleResponse response = dhanApiClient.fetchIntraday(
                        instrument, interval.getDhanApiValue(), chunkStart, chunkEnd);

                List<Candle> candles = candleMapper.map(response, instrument, interval);
                buffer.addAll(candles);

                if (buffer.size() >= properties.getBackfill().getBatchInsertSize()) {
                    inserted += candleRepository.batchInsert(buffer);
                    buffer.clear();
                }

                progressRepository.markDone(
                        instrument.getSecurityId(), intervalKey, chunkStart, chunkEnd, candles.size());

                log.info("  ✓ {}/{} {} → {} | {} candles",
                        instrument.getSymbol(), intervalKey, chunkStart, chunkEnd, candles.size());

            } catch (Exception e) {
                log.error("  ✗ Chunk failed {}/{} {} → {}",
                        instrument.getSymbol(), intervalKey, chunkStart, chunkEnd, e);
                progressRepository.markFailed(
                        instrument.getSecurityId(), intervalKey, chunkStart, chunkEnd);
                failed++;
            }

            chunkStart = chunkEnd.plusDays(1);
            sleep(300);
        }

        if (!buffer.isEmpty()) {
            inserted += candleRepository.batchInsert(buffer);
        }

        int failedCount = progressRepository.countFailed(instrument.getSecurityId(), intervalKey);
        if (failedCount > 0) {
            log.warn("  ⚠ {}/{} has {} failed chunks — re-run backfill to retry",
                    instrument.getSymbol(), intervalKey, failedCount);
        }

        return new BackfillResult(inserted, skipped, failed);
    }

    // ── Daily (1d) ────────────────────────────────────────────────────────────

    /**
     * Fetches daily candles via /v2/charts/historical (no 90-day chunk limit).
     * Resumes from the day after the last DONE progress record.
     * 1W and 1M are NOT fetched here — they are derived from these 1d candles
     * by DerivedCandleService after all instruments are done.
     */
    private BackfillResult backfillDaily(Instrument instrument) {
        final String intervalKey = "1d";

        progressRepository.resetStaleInProgress(instrument.getSecurityId(), intervalKey);

        LocalDate globalEnd   = LocalDate.now();
        LocalDate globalStart = globalEnd.minusYears(properties.getBackfill().getYearsBack());

        Optional<LocalDate> lastDone = progressRepository.getLastCompletedDate(
                instrument.getSecurityId(), intervalKey);

        LocalDate fetchFrom = lastDone
                .map(d -> d.plusDays(1))
                .orElse(globalStart);

        if (!fetchFrom.isBefore(globalEnd)) {
            log.info("✓ {}/1d already fully backfilled up to {}", instrument.getSymbol(), globalEnd);
            return new BackfillResult(0, 1, 0);
        }

        log.info("▶ Backfilling {}/1d | {} → {}", instrument.getSymbol(), fetchFrom, globalEnd);

        try {
            progressRepository.markInProgress(
                    instrument.getSecurityId(), intervalKey, fetchFrom, globalEnd);

            DhanCandleResponse response = dhanApiClient.fetchDaily(instrument, fetchFrom, globalEnd);
            List<Candle> candles = candleMapper.mapWithIntervalKey(response, instrument, intervalKey);

            if (candles.isEmpty()) {
                log.warn("  ⚠ {}/1d returned 0 candles for {} → {}",
                        instrument.getSymbol(), fetchFrom, globalEnd);
                progressRepository.markDone(
                        instrument.getSecurityId(), intervalKey, fetchFrom, globalEnd, 0);
                return new BackfillResult(0, 0, 0);
            }

            int inserted = candleRepository.batchInsert(candles);
            progressRepository.markDone(
                    instrument.getSecurityId(), intervalKey, fetchFrom, globalEnd, inserted);

            log.info("  ✓ {}/1d {} → {} | {} candles",
                    instrument.getSymbol(), fetchFrom, globalEnd, inserted);
            return new BackfillResult(inserted, 0, 0);

        } catch (Exception e) {
            log.error("  ✗ Daily backfill failed for {}", instrument.getSymbol(), e);
            progressRepository.markFailed(instrument.getSecurityId(), intervalKey, fetchFrom, globalEnd);
            return new BackfillResult(0, 0, 1);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean isRunning() { return running; }

    private record BackfillResult(int inserted, int skipped, int failed) {}
}
