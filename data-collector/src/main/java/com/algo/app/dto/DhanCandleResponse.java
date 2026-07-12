package com.algo.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Dhan returns OHLCV as parallel arrays — each index maps to one candle.
 * e.g., open[0], high[0], low[0], close[0], volume[0], timestamp[0] = first candle
 */
@Data
public class DhanCandleResponse {

    @JsonProperty("open")
    private List<Double> open;

    @JsonProperty("high")
    private List<Double> high;

    @JsonProperty("low")
    private List<Double> low;

    @JsonProperty("close")
    private List<Double> close;

    @JsonProperty("volume")
    private List<Long> volume;

    @JsonProperty("oi")
    private List<Long> oi;

    @JsonProperty("timestamp")
    private List<Long> timestamp; // Unix epoch seconds (IST)

    public int size() {
        return timestamp != null ? timestamp.size() : 0;
    }
}
