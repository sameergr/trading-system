package com.algo.app.client;

import com.algo.app.config.DhanProperties;
import com.algo.app.dto.DhanCandleResponse;
import com.algo.app.dto.DhanIntradayRequest;
import com.algo.app.model.Instrument;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
public class DhanApiClient {

    private final WebClient dhanWebClient;
    private final RateLimiter dhanRateLimiter;
    private final DhanProperties properties;

    private static final DateTimeFormatter INTRADAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAILY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Fetch intraday candles for a date range chunk (max 90 days per call).
     */
    public DhanCandleResponse fetchIntraday(Instrument instrument,
                                            String intervalMinutes,
                                            LocalDate from,
                                            LocalDate to) {
        // Market open/close times for NSE
        String fromStr = from.atTime(9, 15, 0).format(INTRADAY_FMT);
        String toStr   = to.atTime(15, 30, 0).format(INTRADAY_FMT);

        DhanIntradayRequest request = DhanIntradayRequest.builder()
                .securityId(String.valueOf(instrument.getSecurityId()))
                .exchangeSegment(instrument.getExchangeSegment())
                .instrument(instrument.getInstrumentType())
                .interval(intervalMinutes)
                .oi(true)
                .fromDate(fromStr)
                .toDate(toStr)
                .build();

        log.debug("Fetching {}-min candles for {} from {} to {}",
                intervalMinutes, instrument.getSymbol(), fromStr, toStr);

        return RateLimiter.decorateSupplier(dhanRateLimiter, () ->
                dhanWebClient.post()
                        .uri("/charts/intraday")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(DhanCandleResponse.class)
                        .retryWhen(Retry.backoff(properties.getApi().getMaxRetries(), Duration.ofSeconds(2))
                                .filter(ex -> ex instanceof WebClientResponseException.TooManyRequests
                                        || ex instanceof WebClientResponseException.ServiceUnavailable))
                        .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                        .block()
        ).get();
    }

    /**
     * Fetch daily candles (can go back to stock inception).
     */
    public DhanCandleResponse fetchDaily(Instrument instrument,
                                         LocalDate from,
                                         LocalDate to) {
        var request = new java.util.HashMap<String, Object>();
        request.put("securityId", String.valueOf(instrument.getSecurityId()));
        request.put("exchangeSegment", instrument.getExchangeSegment());
        request.put("instrument", instrument.getInstrumentType());
        request.put("expiryCode", 0);
        request.put("oi", false);
        request.put("fromDate", from.format(DAILY_FMT));
        request.put("toDate", to.format(DAILY_FMT));

        log.debug("Fetching daily candles for {} from {} to {}",
                instrument.getSymbol(), from, to);

        return RateLimiter.decorateSupplier(dhanRateLimiter, () ->
                dhanWebClient.post()
                        .uri("/charts/historical")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(DhanCandleResponse.class)
                        .retryWhen(Retry.backoff(properties.getApi().getMaxRetries(), Duration.ofSeconds(2))
                                .filter(ex -> ex instanceof WebClientResponseException.TooManyRequests))
                        .timeout(Duration.ofSeconds(properties.getApi().getTimeoutSeconds()))
                        .block()
        ).get();
    }
}