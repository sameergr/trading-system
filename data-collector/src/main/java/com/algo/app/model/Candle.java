package com.algo.app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {
    private Long instrumentId;
    private String symbol;
    private String interval;
    private LocalDateTime ts;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private long oi;
}
