package com.algo.app.candle_collector_service.component;

import com.algo.app.candle_collector_service.service.CandleCollectionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class CandleSyncScheduler {

    private final CandleCollectionService collectionService;
    private final List<String> targetIntervals = List.of("1", "5", "15", "60");

    public CandleSyncScheduler(CandleCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    // Cron runs at 11pm from Monday through Friday
//    @Scheduled(cron = "0 0/5 9-16 * * MON-FRI")
//    public void runIntradaySync() {
//        LocalDateTime now = LocalDateTime.now();
//        LocalDateTime lookbackWindow = now.minusMinutes(15); // Safety margin overlap window
//
//        // Example: Iterate over required target list of tracking scrips or items
//        String sampleSecurityId = "1333"; // HDFC example
//        String segment = "NSE_EQ";
//        String instrument = "EQUITY";
//
//        for (String interval : targetIntervals) {
//            collectionService.backfill5YearsData(sampleSecurityId, segment, instrument, interval);
//            // Replace with localized custom single-window call 'fetchFromDhan' to avoid standard 5 year looping paths
//        }
//    }

    // Macro Historical Sync Engine: Runs once daily at 4:00 PM after EOD settlement
    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void runEODHistoricalSync() {
        String securityId = "1333";
        List<String> macroIntervals = List.of("1d", "1w", "1M");

        for (String interval : macroIntervals) {
            collectionService.backfill5YearsData(securityId, "NSE_EQ", "EQUITY", interval);
        }
    }

}
