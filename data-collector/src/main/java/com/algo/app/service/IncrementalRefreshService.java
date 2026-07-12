package com.algo.app.service;

import com.algo.app.client.DhanApiClient;
import com.algo.app.dto.DhanCandleResponse;
import com.algo.app.model.Candle;
import com.algo.app.model.CandleInterval;
import com.algo.app.model.Instrument;
import com.algo.app.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalRefreshService {

    private final DhanApiClient dhanApiClient;
    private final CandleMapper candleMapper;
    private final CandleRepository candleRepository;
    private final InstrumentService instrumentService;

    /**
     * Runs Mon-Fri at 3:30 PM IST (after NSE market close).
     * Fetches today's candles for all instruments × all intervals and appends to ClickHouse.
     * ReplacingMergeTree ensures no duplicate rows if this runs twice.
     */
    @Scheduled(cron = "${scheduler.incremental-cron}", zone = "Asia/Kolkata")
    public void refresh() {
        log.info("=== Incremental Refresh Starting ===");

        LocalDate today = LocalDate.now();
        List<Instrument> instruments = instrumentService.getAllInstruments();

        for (Instrument instrument : instruments) {
            // Intraday intervals — only fetchable ones (skip 1W and 1M, they are derived)
            for (CandleInterval interval : CandleInterval.values()) {
                if (!interval.isFetchable()) continue;
                try {
                    DhanCandleResponse response = dhanApiClient.fetchIntraday(
                            instrument, interval.getDhanApiValue(), today, today);
                    List<Candle> candles = candleMapper.map(response, instrument, interval);
                    if (!candles.isEmpty()) {
                        candleRepository.batchInsert(candles);
                        log.info("Refreshed {} candles for {} {}",
                                candles.size(), instrument.getSymbol(), interval.getClickhouseValue());
                    }
                } catch (Exception e) {
                    log.error("Refresh failed: {} {}", instrument.getSymbol(),
                            interval.getClickhouseValue(), e);
                }
            }

            // Daily candles use a separate endpoint
            try {
                DhanCandleResponse response = dhanApiClient.fetchDaily(instrument, today, today);
                List<Candle> candles = candleMapper.mapWithIntervalKey(response, instrument, "1d");
                if (!candles.isEmpty()) {
                    candleRepository.batchInsert(candles);
                    log.info("Refreshed {} daily candles for {}", candles.size(), instrument.getSymbol());
                }
            } catch (Exception e) {
                log.error("Daily refresh failed for {}", instrument.getSymbol(), e);
            }
        }

        log.info("=== Incremental Refresh Done ===");
    }
}
