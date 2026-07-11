package com.dhan.collector.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentStatusDto {
    private long   securityId;
    private String symbol;
    private String exchangeSegment;
    private String instrumentType;
    private boolean active;

    /** Per-interval backfill status rows */
    private List<IntervalStatus> intervals;

    /** Rolled-up across all intervals */
    private long totalCandles;
    private String overallStatus; // "COMPLETE" | "PARTIAL" | "PENDING" | "FAILED"

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntervalStatus {
        private String interval;
        private long   candleCount;
        private String lastCandleDate;  // yyyy-MM-dd or null
        private String firstCandleDate; // yyyy-MM-dd or null
        private int    doneChunks;
        private int    failedChunks;
        private int    pendingChunks;
        private String status;          // "COMPLETE" | "IN_PROGRESS" | "FAILED" | "PENDING"
    }
}
