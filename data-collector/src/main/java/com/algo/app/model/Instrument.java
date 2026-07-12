package com.algo.app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Instrument {
    private Long securityId;
    private String symbol;
    private String exchangeSegment;
    private String instrumentType;
}
