package com.dhan.collector.service;

import com.dhan.collector.dto.CandleDto;
import com.dhan.collector.dto.InstrumentDto;
import com.dhan.collector.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleService {

    private final CandleRepository candleRepository;

    public List<CandleDto> getCandles(long instrumentId, String interval,
                                       LocalDate from, LocalDate to) {
        // Cap to a sensible max to avoid massive payloads on fine intervals
        LocalDate cappedFrom = capFrom(interval, from, to);
        log.info("Serving candles: instrumentId={} interval={} from={} to={}",
                instrumentId, interval, cappedFrom, to);
        return candleRepository.findCandles(instrumentId, interval, cappedFrom, to);
    }

    public List<InstrumentDto> getInstruments() {
        return candleRepository.findAllInstruments();
    }

    public CandleRepository.DateRange getDateRange(long instrumentId, String interval) {
        try {
            return candleRepository.getAvailableDateRange(instrumentId, interval);
        } catch (Exception e) {
            log.warn("No data found for instrumentId={} interval={}", instrumentId, interval);
            return new CandleRepository.DateRange(
                    LocalDate.now().minusYears(1), LocalDate.now());
        }
    }

    /**
     * For fine intervals (1m, 5m) limit the default window to avoid returning
     * millions of rows. Coarser intervals (1d, 1W, 1M) can show full history.
     */
    private LocalDate capFrom(String interval, LocalDate from, LocalDate to) {
        return switch (interval) {
            case "1m"  -> to.minusDays(5);       // 5 days max for 1-min
            case "5m"  -> to.minusDays(30);      // 30 days max for 5-min
            case "15m" -> to.minusDays(90);      // 90 days for 15-min
            case "60m" -> to.minusYears(1);      // 1 year for hourly
            default    -> from;                  // 1d / 1W / 1M — use requested range
        };
    }
}
