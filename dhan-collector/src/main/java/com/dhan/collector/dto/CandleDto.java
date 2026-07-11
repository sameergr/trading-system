package com.dhan.collector.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Matches TradingView Lightweight Charts CandlestickData shape:
 * { time: number (unix seconds), open, high, low, close }
 * Volume is passed separately as a histogram series.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandleDto {
    private long time;    // Unix epoch seconds — Lightweight Charts expects this
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
}
