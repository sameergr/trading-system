package com.algo.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "dhan")
public class DhanProperties {

    private Api api = new Api();
    private Backfill backfill = new Backfill();

    @Data
    public static class Api {
        private String baseUrl;
        private String accessToken;
        private int timeoutSeconds = 30;
        private int maxRetries = 3;
    }

    @Data
    public static class Backfill {
        private int yearsBack = 5;
        private int chunkDays = 85;
        private String instrumentsFile;
        private List<Integer> intervals = List.of(1, 5, 15, 60);
        private int batchInsertSize = 5000;
    }
}
