package com.dhan.collector.service;

import com.dhan.collector.client.DhanApiClient;
import com.dhan.collector.config.DhanProperties;
import com.dhan.collector.dto.DhanCandleResponse;
import com.dhan.collector.model.Candle;
import com.dhan.collector.model.CandleInterval;
import com.dhan.collector.model.Instrument;
import com.dhan.collector.repository.BackfillProgressRepository;
import com.dhan.collector.repository.CandleRepository;
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

    private volatile boolean running = false;

    /**
     * Triggers backfill for all instruments × all intervals.
     *
     * Resume behaviour:
     *   - Any chunk with status=DONE is skipped entirely.
     *   - Any chunk left IN_PROGRESS from a previous crashed run is reset to PENDING and retried.
     *   - The run starts from the day after the last DONE chunk's to_date, not from the
     *     absolute beginning, so a restart after 3 months of progress resumes at month 3.
     *
     * Deduplication:
     *   - ClickHouse ReplacingMergeTree deduplicates on (instrument_id, interval, ts) in the
     *     background. For extra safety, CandleRepository uses INSERT ... DEDUP (see repo).
     *   - The progress table's isChunkDone() guard prevents re-fetching from Dhan API at all
     *     for chunks already marked DONE, which is the first and cheapest dedup layer.
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

                // Intraday intervals (fetched directly from Dhan API)
                for (int intervalMinutes : properties.getBackfill().getIntervals()) {
                    CandleInterval interval;
                    try {
                        interval = CandleInterval.fromMinutes(intervalMinutes);
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown interval {}min in config — skipping", intervalMinutes);
                        continue;
                    }

                    if (!interval.isFetchable()) {
                        log.debug("Skipping derived interval {} (aggregated separately)", interval.getClickhouseValue());
                        continue;
                    }

                    BackfillResult result = backfillInstrumentInterval(instrument, interval);
                    totalInserted.addAndGet(result.inserted());
                    totalSkipped.addAndGet(result.skipped());
                    totalFailed.addAndGet(result.failed());
                }

                // Daily candles (separate endpoint, no 90-day chunk restriction)
                BackfillResult dailyResult = backfillDaily(instrument);
                totalInserted.addAndGet(dailyResult.inserted());
                totalSkipped.addAndGet(dailyResult.skipped());
                totalFailed.addAndGet(dailyResult.failed());
            }
        } finally {
            // Always clear running flag even if unexpected exception occurs
            running = false;
        }

        log.info("╔══════════════════════════════════════╗");
        log.info("║        BACKFILL COMPLETE             ║");
        log.info("║  Inserted : {}                       ", totalInserted.get());
        log.info("║  Skipped  : {} (already done)        ", totalSkipped.get());
        log.info("║  Failed   : {} chunks                ", totalFailed.get());
        log.info("╚══════════════════════════════════════╝");
    }

    // ─── Intraday backfill ────────────────────────────────────────────────────

    private BackfillResult backfillInstrumentInterval(Instrument instrument, CandleInterval interval) {
        String intervalKey = interval.getClickhouseValue();

        // 1. Reset any stale IN_PROGRESS chunks from a previous crashed run
        progressRepository.resetStaleInProgress(instrument.getSecurityId(), intervalKey);

        // 2. Find where the last successful chunk ended — resume from there
        LocalDate globalEnd   = LocalDate.now();
        LocalDate globalStart = globalEnd.minusYears(properties.getBackfill().getYearsBack());

        Optional<LocalDate> lastDone = progressRepository.getLastCompletedDate(
                instrument.getSecurityId(), intervalKey);

        LocalDate resumeFrom = lastDone
                .map(d -> d.plusDays(1))   // resume the day after last completed chunk
                .orElse(globalStart);       // nothing done yet — start from the beginning

        if (!resumeFrom.isBefore(globalEnd)) {
            log.info("✓ {}/{} already fully backfilled up to {}",
                    instrument.getSymbol(), intervalKey, globalEnd);
            return new BackfillResult(0, 1, 0);
        }

        log.info("▶ Backfilling {}/{} | {} → {} (resuming from {})",
                instrument.getSymbol(), intervalKey, globalStart, globalEnd, resumeFrom);

        // 3. Walk forward in chunks from resumeFrom to globalEnd
        int chunkDays   = properties.getBackfill().getChunkDays();
        List<Candle> buffer = new ArrayList<>();
        int inserted = 0, skipped = 0, failed = 0;

        LocalDate chunkStart = resumeFrom;
        while (chunkStart.isBefore(globalEnd)) {
            LocalDate chunkEnd = chunkStart.plusDays(chunkDays - 1);
            if (chunkEnd.isAfter(globalEnd)) chunkEnd = globalEnd;

            // Exact-match dedup guard (handles edge case where chunk boundaries changed)
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

                // Flush buffer at batch size to avoid holding too much in memory
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
                // Continue to next chunk — don't abort the whole instrument
            }

            chunkStart = chunkEnd.plusDays(1);
            sleep(300); // polite delay between API calls
        }

        // Flush remaining buffer
        if (!buffer.isEmpty()) {
            inserted += candleRepository.batchInsert(buffer);
        }

        int failedCount = progressRepository.countFailed(instrument.getSecurityId(), intervalKey);
        if (failedCount > 0) {
            log.warn("  ⚠ {}/{} has {} failed chunks — re-run backfill to retry them",
                    instrument.getSymbol(), intervalKey, failedCount);
        }

        return new BackfillResult(inserted, skipped, failed);
    }

    // ─── Daily backfill ───────────────────────────────────────────────────────

    /**
     * Daily candles have no 90-day restriction, so we fetch the entire remaining range
     * in one call rather than chunking. We still track progress to avoid re-fetching
     * on restart.
     *
     * Strategy: if ANY daily candles exist for this instrument, only fetch from
     * the last known date forward (incremental). First run fetches full history.
     */
    private BackfillResult backfillDaily(Instrument instrument) {
        String intervalKey = "1d";

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

            // Use CandleInterval.ONE_DAY equivalent — map as "1d"
            List<Candle> candles = candleMapper.mapWithIntervalKey(response, instrument, intervalKey);

            if (candles.isEmpty()) {
                log.warn("  ⚠ {}/1d returned 0 candles for {} → {}", instrument.getSymbol(), fetchFrom, globalEnd);
                progressRepository.markDone(instrument.getSecurityId(), intervalKey, fetchFrom, globalEnd, 0);
                return new BackfillResult(0, 0, 0);
            }

            int inserted = candleRepository.batchInsert(candles);

            progressRepository.markDone(
                    instrument.getSecurityId(), intervalKey, fetchFrom, globalEnd, inserted);

            log.info("  ✓ {}/1d {} → {} | {} candles", instrument.getSymbol(), fetchFrom, globalEnd, inserted);
            return new BackfillResult(inserted, 0, 0);

        } catch (Exception e) {
            log.error("  ✗ Daily backfill failed for {}", instrument.getSymbol(), e);
            progressRepository.markFailed(instrument.getSecurityId(), intervalKey, fetchFrom, globalEnd);
            return new BackfillResult(0, 0, 1);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean isRunning() { return running; }

    /** Lightweight result carrier — avoids mutable counters passed around. */
    private record BackfillResult(int inserted, int skipped, int failed) {}
}
