package com.algo.app.repository;

import com.algo.app.dto.AddInstrumentRequest;
import com.algo.app.dto.InstrumentStatusDto;
import com.algo.app.dto.InstrumentStatusDto.IntervalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class InstrumentManagementRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final List<String> ALL_INTERVALS =
            List.of("1m", "5m", "15m", "1h", "1D", "1W", "1M");

    // ── Read ─────────────────────────────────────────────────────────────────

    /** All instruments with their per-interval backfill status. */
    public List<InstrumentStatusDto> findAllWithStatus() {
        // 1. Fetch all instruments
        List<InstrumentStatusDto> instruments = jdbcTemplate.query("""
            SELECT security_id, symbol, exchange_segment, instrument_type, active
            FROM instruments FINAL
            ORDER BY symbol ASC
            """,
            (rs, i) -> InstrumentStatusDto.builder()
                    .securityId(rs.getLong("security_id"))
                    .symbol(rs.getString("symbol"))
                    .exchangeSegment(rs.getString("exchange_segment"))
                    .instrumentType(rs.getString("instrument_type"))
                    .active(rs.getByte("active") == 1)
                    .build()
        );

        // 2. Enrich each with interval-level status
        for (InstrumentStatusDto inst : instruments) {
            List<IntervalStatus> intervalStatuses = ALL_INTERVALS.stream()
                    .map(interval -> buildIntervalStatus(inst.getSecurityId(), interval))
                    .toList();

            long totalCandles = intervalStatuses.stream()
                    .mapToLong(IntervalStatus::getCandleCount).sum();

            inst.setIntervals(intervalStatuses);
            inst.setTotalCandles(totalCandles);
            inst.setOverallStatus(deriveOverallStatus(intervalStatuses));
        }

        return instruments;
    }

    private IntervalStatus buildIntervalStatus(long securityId, String interval) {
        // Candle count + date range from candles table
        record CandleStats(long count, String minDate, String maxDate) {}
        CandleStats stats = jdbcTemplate.queryForObject("""
            SELECT
                count()                             AS cnt,
                toString(toDate(min(ts)))           AS min_date,
                toString(toDate(max(ts)))           AS max_date
            FROM trading.candles FINAL
            WHERE instrument_id = ? AND interval = ?
            """,
            (rs, i) -> new CandleStats(
                    rs.getLong("cnt"),
                    rs.getString("min_date"),
                    rs.getString("max_date")
            ),
            securityId, interval
        );

        // Chunk progress from backfill_progress table
        record ChunkStats(int done, int failed, int pending) {}
        ChunkStats chunks = jdbcTemplate.queryForObject("""
            SELECT
                countIf(status = 'DONE')        AS done,
                countIf(status = 'FAILED')      AS failed,
                countIf(status = 'PENDING'
                     OR status = 'IN_PROGRESS') AS pending
            FROM trading.backfill_progress FINAL
            WHERE instrument_id = ? AND interval = ?
            """,
            (rs, i) -> new ChunkStats(
                    rs.getInt("done"),
                    rs.getInt("failed"),
                    rs.getInt("pending")
            ),
            securityId, interval
        );

        String status;
        if (stats.count() == 0 && chunks.done() == 0) {
            status = "PENDING";
        } else if (chunks.failed() > 0 && chunks.done() == 0) {
            status = "FAILED";
        } else if (chunks.failed() > 0 || chunks.pending() > 0) {
            status = "PARTIAL";
        } else if (stats.count() > 0) {
            status = "COMPLETE";
        } else {
            status = "PENDING";
        }

        return IntervalStatus.builder()
                .interval(interval)
                .candleCount(stats.count())
                .firstCandleDate("0001-01-01".equals(stats.minDate()) ? null : stats.minDate())
                .lastCandleDate("0001-01-01".equals(stats.maxDate()) ? null : stats.maxDate())
                .doneChunks(chunks.done())
                .failedChunks(chunks.failed())
                .pendingChunks(chunks.pending())
                .status(status)
                .build();
    }

    private String deriveOverallStatus(List<IntervalStatus> intervals) {
        boolean anyComplete = intervals.stream().anyMatch(i -> "COMPLETE".equals(i.getStatus()));
        boolean anyFailed   = intervals.stream().anyMatch(i -> "FAILED".equals(i.getStatus()));
        boolean anyPartial  = intervals.stream().anyMatch(i -> "PARTIAL".equals(i.getStatus()));
        boolean allPending  = intervals.stream().allMatch(i -> "PENDING".equals(i.getStatus()));

        if (allPending)  return "PENDING";
        if (anyFailed && !anyComplete) return "FAILED";
        if (anyPartial || (anyFailed && anyComplete)) return "PARTIAL";
        if (anyComplete) return "COMPLETE";
        return "PENDING";
    }

    // ── Write ────────────────────────────────────────────────────────────────

    public void insertInstrument(AddInstrumentRequest req) {
        jdbcTemplate.update("""
            INSERT INTO trading.instruments
                (security_id, symbol, exchange_segment, instrument_type, active, created_at)
            VALUES (?, ?, ?, ?, 1, now())
            """,
            req.getSecurityId(),
            req.getSymbol().toUpperCase().trim(),
            req.getExchangeSegment().trim(),
            req.getInstrumentType().trim()
        );
    }

    public void insertInstruments(List<AddInstrumentRequest> reqs) {
        List<Object[]> batch = reqs.stream()
                .map(r -> new Object[]{
                        r.getSecurityId(),
                        r.getSymbol().toUpperCase().trim(),
                        r.getExchangeSegment().trim(),
                        r.getInstrumentType().trim()
                }).toList();

        jdbcTemplate.batchUpdate("""
            INSERT INTO trading.instruments
                (security_id, symbol, exchange_segment, instrument_type, active, created_at)
            VALUES (?, ?, ?, ?, 1, now())
            """, batch
        );
    }

    public void deactivateInstrument(long securityId) {
        jdbcTemplate.update("""
            INSERT INTO trading.instruments
                (security_id, symbol, exchange_segment, instrument_type, active, created_at)
            SELECT security_id, symbol, exchange_segment, instrument_type, 0, now()
            FROM trading.instruments FINAL
            WHERE security_id = ?
            """, securityId
        );
    }

    public boolean exists(long securityId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count() FROM trading.instruments FINAL WHERE security_id = ?",
                Integer.class, securityId
        );
        return count != null && count > 0;
    }
}
