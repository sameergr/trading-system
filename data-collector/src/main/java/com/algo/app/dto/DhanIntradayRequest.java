package com.algo.app.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DhanIntradayRequest {

    @JsonProperty("securityId")
    private String securityId;

    @JsonProperty("exchangeSegment")
    private String exchangeSegment;

    @JsonProperty("instrument")
    private String instrument;

    @JsonProperty("interval")
    private String interval;  // "1", "5", "15", "25", "60"

    @JsonProperty("oi")
    private boolean oi;

    @JsonProperty("fromDate")
    private String fromDate;  // "yyyy-MM-dd HH:mm:ss"

    @JsonProperty("toDate")
    private String toDate;
}
