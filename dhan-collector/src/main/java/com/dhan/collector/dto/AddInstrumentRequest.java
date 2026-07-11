package com.dhan.collector.dto;

import lombok.Data;

@Data
public class AddInstrumentRequest {
    private long   securityId;
    private String symbol;
    private String exchangeSegment;  // NSE_EQ | BSE_EQ | NSE_FNO | ...
    private String instrumentType;   // EQUITY | FUTIDX | OPTIDX | ...
}
