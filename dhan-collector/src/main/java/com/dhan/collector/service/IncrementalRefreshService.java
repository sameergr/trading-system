package com.dhan.collector.service;

import com.dhan.collector.client.DhanApiClient;
import com.dhan.collector.dto.DhanCandleResponse;
import com.dhan.collector.model.Candle;
import com.dhan.collector.model.CandleInterval;
import com.dhan.collector.model.Instrument;
import com.dhan.collector.repository.CandleRepository;
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
            for (CandleInterval interval : CandleInterval.values()) {
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
        }

        log.info("=== Incremental Refresh Done ===");
    }
}
