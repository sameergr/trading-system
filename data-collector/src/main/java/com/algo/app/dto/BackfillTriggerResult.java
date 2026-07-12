package com.algo.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackfillTriggerResult {
    private String       status;    // QUEUED | SKIPPED
    private String       message;
    private List<String> symbols;   // symbols queued
}
