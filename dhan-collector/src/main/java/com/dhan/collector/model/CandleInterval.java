package com.dhan.collector.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum CandleInterval {
    ONE_MIN(1,     "1m",  "1",    false),
    FIVE_MIN(5,    "5m",  "5",    false),
    FIFTEEN_MIN(15,"15m", "15",   false),
    ONE_HOUR(60,   "60m", "60",   false),
    ONE_WEEK(10080,"1W",  null,   true),   // derived by aggregating daily candles
    ONE_MONTH(43200,"1M", null,   true);   // derived by aggregating daily candles

    private final int minutes;
    private final String clickhouseValue; // stored in ClickHouse
    private final String dhanApiValue;    // sent to Dhan API (null = derived, not fetched directly)
    private final boolean derived;        // if true, aggregated from daily data, not fetched from API

    public boolean isFetchable() {
        return !derived;
    }

    public static CandleInterval fromMinutes(int minutes) {
        return Arrays.stream(values())
                .filter(i -> i.minutes == minutes)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown interval: " + minutes));
    }

    public static CandleInterval fromClickhouseValue(String value) {
        return Arrays.stream(values())
                .filter(i -> i.clickhouseValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown interval value: " + value));
    }
}
