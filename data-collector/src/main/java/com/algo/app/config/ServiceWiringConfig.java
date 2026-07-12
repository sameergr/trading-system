package com.algo.app.config;

import com.algo.app.service.BackfillService;
import com.algo.app.service.DerivedCandleService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Wires DerivedCandleService into BackfillService after both beans are fully
 * constructed, avoiding a circular dependency between them.
 *
 * BackfillService calls derivedCandleService.aggregateNow() at the end of a
 * full backfill run so 1W and 1M candles are always derived automatically.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class ServiceWiringConfig implements ApplicationRunner {

    private final BackfillService backfillService;
    private final DerivedCandleService derivedCandleService;

    @Override
    public void run(ApplicationArguments args) {
        backfillService.setDerivedCandleService(derivedCandleService);
    }
}
