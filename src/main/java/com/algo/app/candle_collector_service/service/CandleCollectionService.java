package com.algo.app.candle_collector_service.service;

import com.algo.app.candle_collector_service.dto.DhanCandleRequest;
import com.algo.app.candle_collector_service.model.Candle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CandleCollectionService {

    @Value("${dhan.api.intraday-url}")
    private String intradayUrl;

    @Value("${dhan.api.historical-url}")
    private String historicalUrl;

    @Value("${dhan.api.access-token}")
    private String accessToken;

    private final RestClient restClient = RestClient.create();
    private final JdbcTemplate jdbcTemplate;

    // Formatters optimized for Dhan API rules
    private final DateTimeFormatter intradayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DateTimeFormatter historicalFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public CandleCollectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Entrypoint for pulling 5 years of historical candle records.
     */
    public void backfill5YearsData(String securityId, String exchangeSegment, String instrument, String interval) {
        LocalDateTime endDate = LocalDateTime.now();
        LocalDateTime startDate = endDate.minusYears(5);

        if (isHistoricalInterval(interval)) {
            // Macro Intervals (1D, 1W, 1M) can be requested all in one shot
            System.out.printf("🚀 Fetching Historical Macro Data (%s) from %s to %s%n",
                    instrument, startDate.format(historicalFormatter), endDate.format(historicalFormatter));

            List<Candle> candles = fetchFromDhanWithRetry(historicalUrl, securityId, exchangeSegment, instrument, interval, startDate, endDate);
            if (!candles.isEmpty()) {
                saveToClickHouse(candles);
            }
        } else {
            // Intraday Intervals (5m, 15m, 60m) must be fetched in maximum 90-day segments
            LocalDateTime currentTo = endDate;
            while (currentTo.isAfter(startDate)) {
                LocalDateTime currentFrom = currentTo.minusDays(90).isBefore(startDate) ? startDate : currentTo.minusDays(90);

                System.out.printf("⏳ Fetching Intraday Data Segment (%s) from %s to %s%n",
                        interval, currentFrom.format(intradayFormatter), currentTo.format(intradayFormatter));

                List<Candle> candles = fetchFromDhanWithRetry(intradayUrl, securityId, exchangeSegment, instrument, interval, currentFrom, currentTo);

                if (!candles.isEmpty()) {
                    saveToClickHouse(candles);
                }

                currentTo = currentFrom;

                // Mandatory delay loop spacing to prevent hitting rolling rate-limits (429)
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Executes HTTP POST payload request wrapped in an aggressive anti-429 backoff retry block.
     */
    private List<Candle> fetchFromDhanWithRetry(String url, String securityId, String exchangeSegment,
                                                    String instrument, String interval, LocalDateTime from, LocalDateTime to) {
        boolean isMacro = isHistoricalInterval(interval);
        String startStr = from.format(isMacro ? historicalFormatter : intradayFormatter);
        String endStr = to.format(isMacro ? historicalFormatter : intradayFormatter);

        DhanCandleRequest requestBody = new DhanCandleRequest(
                securityId, exchangeSegment, instrument, interval, false, startStr, endStr
        );

        int retryCount = 0;
        int maxRetries = 4;

        while (retryCount < maxRetries) {
            try {
                ResponseEntity<Map<String, Object>> responseEntity = restClient.post()
                        .uri(url)
                        .header("access-token", accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});

                // Gracefully intercept 429 Too Many Requests status code
                if (responseEntity.getStatusCode().value() == 429) {
                    System.out.printf("⚠️ [Rate Limit 429 Hit] Backing off for 6 seconds (Attempt %d/%d)...%n", retryCount + 1, maxRetries);
                    Thread.sleep(6000);
                    retryCount++;
                    continue;
                }

                Map<String, Object> responseBody = responseEntity.getBody();
                if (responseBody != null) {
                    return mapResponseToCandles(responseBody, securityId, exchangeSegment, interval);
                }

                break; // Break loop if response was parsed or empty status encountered
            } catch (Exception e) {
                System.err.println("❌ Network or API error occurred: " + e.getMessage());
                retryCount++;
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        return List.of();
    }

    /**
     * Parses Dhan's JSON Response Arrays into separate clean ClickHouse records.
     */
    @SuppressWarnings("unchecked")
    private List<Candle> mapResponseToCandles(Map<String, Object> response, String instrumentId, String segment, String interval) {
        List<Candle> recordList = new ArrayList<>();
        try {
            List<Number> openPrices = (List<Number>) response.get("open");
            List<Number> highPrices = (List<Number>) response.get("high");
            List<Number> lowPrices = (List<Number>) response.get("low");
            List<Number> closePrices = (List<Number>) response.get("close");
            List<Number> volumes = (List<Number>) response.get("volume");
            List<Number> timestamps = (List<Number>) response.get("timestamp");
            List<Number> oi = (List<Number>) response.get("open_interest");

            for (int i = 0; i < openPrices.size(); i++) {
                // Convert Dhan Epoch timestamp (Seconds) to Java LocalDateTime
                Number openPrice = openPrices.get(i).longValue();

                Candle candle = new Candle(
                        instrumentId,
                        interval,
                        LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamps.get(i).longValue()), ZoneId.systemDefault()),
                        openPrice.doubleValue(),
                        highPrices.get(i).doubleValue(),
                        lowPrices.get(i).doubleValue(),
                        closePrices.get(i).doubleValue(),
                        volumes.get(i).longValue(),
                        oi != null ? oi.get(i).longValue() : 0L
                );
                recordList.add(candle);
            }
        } catch (Exception e) {
            System.err.println("💥 Failed to parse JSON fields: " + e.getMessage());
        }
        return recordList;
    }

    /**
     * Saves list collections efficiently via JDBC Batch Updates to ClickHouse.
     */
    public void saveToClickHouse(List<Candle> candles) {
        String sql = "INSERT INTO trading.candles (instrument_id, `interval`, ts, `open`, high, low, `close`, volume, oi) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try {
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Candle candle = candles.get(i);
                    ps.setString(1, candle.instrumentId());
                    ps.setString(2, candle.interval());
                    ps.setString(3, Timestamp.valueOf(candle.timestamp()).toString());
                    ps.setDouble(4, candle.open());
                    ps.setDouble(5, candle.high());
                    ps.setDouble(6, candle.low());
                    ps.setDouble(7, candle.close());
                    ps.setLong(8, candle.volume());
                    ps.setLong(9, candle.oi());
                }

                @Override
                public int getBatchSize() {
                    return candles.size();
                }
            });
            System.out.printf("💾 Successfully stored [%d] bars inside ClickHouse table.%n", candles.size());
        } catch (Exception e) {
            System.err.println(candles.get(candles.size() - 1));
            System.err.println("❌ ClickHouse Batch Write Failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean isHistoricalInterval(String interval) {
        return "1d".equals(interval) || "DAILY".equals(interval) ||
                "1w".equals(interval) || "WEEKLY".equals(interval) ||
                "1M".equals(interval) || "MONTHLY".equals(interval);
    }
}
