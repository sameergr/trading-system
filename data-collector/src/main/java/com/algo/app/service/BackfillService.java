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

    private DerivedCandleService derivedCandleService;

    public void setDerivedCandleService(DerivedCandleService svc) {
        this.derivedCandleService = svc;
    }

    private volatile boolean running = false;

    /**
     * Full backfill:
     *   Step 1 — Intraday (1m, 5m, 15m, 60m) via /charts/intraday, 85-day chunks
     *   Step 2 — Daily   (1d)                 via /charts/historical, single call
     *   Step 3 — Weekly  (1W) + Monthly (1M)  derived from 1d via ClickHouse aggregation
     *
     * 1W and 1M are NEVER fetched from Dhan — Dhan has no such endpoint.
     * They are computed entirely from stored 1d rows by DerivedCandleService.
     */
    public void runBackfill() {
        if (running) {
            log.warn("Backfill already in progress, skipping.");
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
        log.info("Instruments to backfill: {}", instruments.size());

        try {
            for (Instrument instrument : instruments) {
                log.info("── Starting instrument: {} (id={}) ──",
                        instrument.getSymbol(), instrument.getSecurityId());

                // ── Step 1: Intraday ────────────────────────────────────────
                for (String interval : properties.getBackfill().getIntervals()) {
                    CandleInterval candleInterval;
                    try {
                        candleInterval = CandleInterval.fromInterval(interval);
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown interval {}min — skipping", interval);
                        continue;
                    }
                    if (!candleInterval.isFetchable()) continue;

                    BackfillResult r = backfillIntraday(instrument, candleInterval);
                    totalInserted.addAndGet(r.inserted());
                    totalSkipped.addAndGet(r.skipped());
                    totalFailed.addAndGet(r.failed());
                }

                // ── Step 2: Daily (1d) ─────────────────────────────────────
                BackfillResult r = backfillDaily(instrument);
                totalInserted.addAndGet(r.inserted());
                totalSkipped.addAndGet(r.skipped());
                totalFailed.addAndGet(r.failed());
            }

            // ── Step 3: Derive 1W and 1M from stored 1d rows ───────────────
            // Must run AFTER all instruments' 1d candles are inserted.
            log.info("══ Step 3: Deriving 1W and 1M from 1d data ══");
            if (derivedCandleService != null) {
                derivedCandleService.aggregateNow();
                log.info("✓ 1W and 1M derived successfully.");
            } else {
                log.warn("DerivedCandleService not wired. Call POST /api/backfill/derive manually.");
            }

        } finally {
            running = false;
        }

        log.info("╔══════════════════════════════════════╗");
        log.info("║  BACKFILL COMPLETE                   ║");
        log.info("║  Inserted : {}",  totalInserted.get());
        log.info("║  Skipped  : {}",  totalSkipped.get());
        log.info("║  Failed   : {}",  totalFailed.get());
        log.info("╚══════════════════════════════════════╝");
    }

    // ── Intraday ──────────────────────────────────────────────────────────────

    private BackfillResult backfillIntraday(Instrument instrument, CandleInterval interval) {
        String key = interval.getClickhouseValue();
        progressRepository.resetStaleInProgress(instrument.getSecurityId(), key);

        LocalDate end   = LocalDate.now();
        LocalDate start = end.minusYears(properties.getBackfill().getYearsBack());

        LocalDate resumeFrom = progressRepository
                .getLastCompletedDate(instrument.getSecurityId(), key)
                .map(d -> d.plusDays(1))
                .orElse(start);

        if (!resumeFrom.isBefore(end)) {
            log.info("✓ {}/{} already complete", instrument.getSymbol(), key);
            return new BackfillResult(0, 1, 0);
        }

        log.info("▶ {}/{} resuming from {}", instrument.getSymbol(), key, resumeFrom);

        int chunkDays = properties.getBackfill().getChunkDays();
        List<Candle> buffer = new ArrayList<>();
        int inserted = 0, skipped = 0, failed = 0;
        LocalDate chunkStart = resumeFrom;

        while (chunkStart.isBefore(end)) {
            LocalDate chunkEnd = chunkStart.plusDays(chunkDays - 1);
            if (chunkEnd.isAfter(end)) chunkEnd = end;

            if (progressRepository.isChunkDone(instrument.getSecurityId(), key, chunkStart, chunkEnd)) {
                skipped++;
                chunkStart = chunkEnd.plusDays(1);
                continue;
            }

            try {
                progressRepository.markInProgress(instrument.getSecurityId(), key, chunkStart, chunkEnd);

                DhanCandleResponse response = dhanApiClient.fetchIntraday(
                        instrument, interval.getDhanApiValue(), chunkStart, chunkEnd);
                List<Candle> candles = candleMapper.map(response, instrument, interval);
                buffer.addAll(candles);

                if (buffer.size() >= properties.getBackfill().getBatchInsertSize()) {
                    inserted += candleRepository.batchInsert(buffer);
                    buffer.clear();
                }

                progressRepository.markDone(instrument.getSecurityId(), key, chunkStart, chunkEnd, candles.size());
                log.info("  ✓ {}/{} {} → {} | {} candles", instrument.getSymbol(), key, chunkStart, chunkEnd, candles.size());

            } catch (Exception e) {
                log.error("  ✗ {}/{} {} → {} failed", instrument.getSymbol(), key, chunkStart, chunkEnd, e);
                progressRepository.markFailed(instrument.getSecurityId(), key, chunkStart, chunkEnd);
                failed++;
            }

            chunkStart = chunkEnd.plusDays(1);
            sleep(300);
        }

        if (!buffer.isEmpty()) inserted += candleRepository.batchInsert(buffer);
        return new BackfillResult(inserted, skipped, failed);
    }

    // ── Daily (1d) ────────────────────────────────────────────────────────────

    /**
     * Fetches 1d candles via /charts/historical for the full date range in one call.
     * Resume: only fetches from day after last DONE record.
     *
     * NOTE: 1W and 1M are NOT fetched here or anywhere else from Dhan.
     * They are derived in Step 3 by DerivedCandleService.aggregateNow().
     */
    private BackfillResult backfillDaily(Instrument instrument) {
        String key = "1D";
        progressRepository.resetStaleInProgress(instrument.getSecurityId(), key);
        
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(properties.getBackfill().getYearsBack());

        LocalDate fetchFrom = progressRepository
                .getLastCompletedDate(instrument.getSecurityId(), key)
                .map(d -> d.plusDays(1))
                .orElse(start);

        if (!fetchFrom.isBefore(end)) {
            log.info("✓ {}/1D already complete", instrument.getSymbol());
            return new BackfillResult(0, 1, 0);
        }

        log.info("▶ {}/1D fetching {} → {}", instrument.getSymbol(), fetchFrom, end);

        try {
            progressRepository.markInProgress(instrument.getSecurityId(), key, fetchFrom, end);

            DhanCandleResponse response = dhanApiClient.fetchDaily(instrument, fetchFrom, end);
            List<Candle> candles = candleMapper.mapWithIntervalKey(response, instrument, key);

            if (candles.isEmpty()) {
                log.warn("  ⚠ {}/1D returned 0 candles — check securityId={} exchangeSegment={} instrumentType={}",
                        instrument.getSymbol(),
                        instrument.getSecurityId(),
                        instrument.getExchangeSegment(),
                        instrument.getInstrumentType());
                // Mark as failed so it retries on next run rather than being silently skipped
                progressRepository.markFailed(instrument.getSecurityId(), key, fetchFrom, end);
                return new BackfillResult(0, 0, 1);
            }

            int inserted = candleRepository.batchInsert(candles);
            progressRepository.markDone(instrument.getSecurityId(), key, fetchFrom, end, inserted);
            log.info("  ✓ {}/1D inserted {} candles ({} → {})",
                    instrument.getSymbol(), inserted, candles.get(0).getTs(), candles.get(candles.size() - 1).getTs());
            return new BackfillResult(inserted, 0, 0);

        } catch (Exception e) {
            log.error("  ✗ {}/1D fetch failed", instrument.getSymbol(), e);
            progressRepository.markFailed(instrument.getSecurityId(), key, fetchFrom, end);
            return new BackfillResult(0, 0, 1);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean isRunning() { return running; }

    private record BackfillResult(int inserted, int skipped, int failed) {}
}
