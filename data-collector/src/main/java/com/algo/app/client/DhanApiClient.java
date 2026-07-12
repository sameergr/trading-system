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
import java.util.LinkedHashMap;
import java.util.Map;

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
                .oi(false)
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

    // ── Daily (1d) ────────────────────────────────────────────────────────────

    /**
     * Fetch daily candles via /v2/charts/historical.
     *
     * Key differences from intraday:
     *  - Different endpoint: /charts/historical (not /charts/intraday)
     *  - Date format: yyyy-MM-dd (not yyyy-MM-dd HH:mm:ss)
     *  - No interval field — this endpoint always returns daily candles
     *  - expiryCode only included for F&O instruments — sending it for equity
     *    causes Dhan to return an empty response silently
     *  - oi flag omitted — not applicable for daily equity data
     */
    public DhanCandleResponse fetchDaily(Instrument instrument,
                                         LocalDate from,
                                         LocalDate to) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("securityId",      String.valueOf(instrument.getSecurityId()));
        request.put("exchangeSegment", instrument.getExchangeSegment());
        request.put("instrument",      instrument.getInstrumentType());
        request.put("fromDate",        from.format(DAILY_FMT));
        request.put("toDate",          to.format(DAILY_FMT));

        // expiryCode is required only for F&O — omitting it for equity
        // avoids the silent-empty-response bug in Dhan's API
        if (isFnO(instrument)) {
            request.put("expiryCode", 0);
        }

        log.info("Fetching 1D candles: symbol={} exchange={} type={} from={} to={}",
                instrument.getSymbol(), instrument.getExchangeSegment(),
                instrument.getInstrumentType(), from, to);

        DhanCandleResponse response = RateLimiter.decorateSupplier(dhanRateLimiter, () ->
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

        int count = (response != null) ? response.size() : 0;
        log.info("1D response: symbol={} candles={}", instrument.getSymbol(), count);

        if (count == 0) {
            log.warn("1D returned 0 candles for {} [{} → {}]. " +
                     "Verify: securityId={}, exchangeSegment={}, instrumentType={}",
                    instrument.getSymbol(), from, to,
                    instrument.getSecurityId(),
                    instrument.getExchangeSegment(),
                    instrument.getInstrumentType());
        }

        return response;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isFnO(Instrument instrument) {
        String seg  = instrument.getExchangeSegment().toUpperCase();
        String type = instrument.getInstrumentType().toUpperCase();
        return seg.contains("FNO") || seg.contains("IDX")
            || type.startsWith("FUT") || type.startsWith("OPT");
    }
}
