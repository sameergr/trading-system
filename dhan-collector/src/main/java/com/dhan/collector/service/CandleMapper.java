package com.dhan.collector.service;

import com.dhan.collector.dto.DhanCandleResponse;
import com.dhan.collector.model.Candle;
import com.dhan.collector.model.CandleInterval;
import com.dhan.collector.model.Instrument;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CandleMapper {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Maps Dhan's parallel-array response to a list of Candle objects.
     * Dhan returns {open:[], high:[], low:[], close:[], volume:[], timestamp:[]}
     * where index i across all arrays = candle i.
     */
    public List<Candle> map(DhanCandleResponse response,
                             Instrument instrument,
                             CandleInterval interval) {
        return mapWithIntervalKey(response, instrument, interval.getClickhouseValue());
    }

    /**
     * Maps using a raw interval key string — used for daily ('1d') and derived intervals
     * that don't have a CandleInterval enum entry covering them directly.
     */
    public List<Candle> mapWithIntervalKey(DhanCandleResponse response,
                                            Instrument instrument,
                                            String intervalKey) {
        if (response == null || response.size() == 0) {
            return Collections.emptyList();
        }

        List<Candle> candles = new ArrayList<>(response.size());
        for (int i = 0; i < response.size(); i++) {
            LocalDateTime ts = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(response.getTimestamp().get(i)), IST);

            long oi = (response.getOi() != null && response.getOi().size() > i)
                    ? response.getOi().get(i) : 0L;

            candles.add(Candle.builder()
                    .instrumentId(instrument.getSecurityId())
                    .symbol(instrument.getSymbol())
                    .interval(intervalKey)
                    .ts(ts)
                    .open(response.getOpen().get(i))
                    .high(response.getHigh().get(i))
                    .low(response.getLow().get(i))
                    .close(response.getClose().get(i))
                    .volume(response.getVolume().get(i))
                    .oi(oi)
                    .build());
        }
        return candles;
    }
}
