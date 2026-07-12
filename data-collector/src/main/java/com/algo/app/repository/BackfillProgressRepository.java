package com.algo.app.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class BackfillProgressRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns the latest date that has been fully backfilled for this instrument+interval.
     * On resume, backfill starts from the day AFTER this date.
     * Returns empty if no progress exists yet (start from scratch).
     */
    public Optional<LocalDate> getLastCompletedDate(long instrumentId, String interval) {
        try {
            LocalDate date = jdbcTemplate.queryForObject("""
                SELECT max(to_date)
                FROM trading.backfill_progress FINAL
                WHERE instrument_id = ?
                  AND interval      = ?
                  AND status        = 'DONE'
                """,
                java.sql.Date.class,
                instrumentId, interval
            ).toLocalDate();
            return Optional.ofNullable(date);
        } catch (Exception e) {
            // queryForObject throws if result is null (no rows) — that's fine, means no progress yet
            return Optional.empty();
        }
    }

    /**
     * Returns true only if this exact chunk was successfully completed.
     * Used for deduplication guard on the current run's in-flight chunks.
     */
    public boolean isChunkDone(long instrumentId, String interval, LocalDate from, LocalDate to) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT count()
            FROM trading.backfill_progress FINAL
            WHERE instrument_id = ?
              AND interval      = ?
              AND from_date     = ?
              AND to_date       = ?
              AND status        = 'DONE'
            """,
            Integer.class,
            instrumentId, interval,
            java.sql.Date.valueOf(from),
            java.sql.Date.valueOf(to)
        );
        return count != null && count > 0;
    }

    /**
     * Resets any IN_PROGRESS chunks for this instrument+interval back to PENDING
     * so they are retried on resume. Called at the start of each backfill run.
     * Handles the case where the previous run crashed mid-chunk.
     */
    public void resetStaleInProgress(long instrumentId, String interval) {
        jdbcTemplate.update("""
            INSERT INTO trading.backfill_progress
                (instrument_id, interval, from_date, to_date, status, rows_inserted, updated_at)
            SELECT
                instrument_id,
                interval,
                from_date,
                to_date,
                'PENDING',
                0,
                now()
            FROM trading.backfill_progress FINAL
            WHERE instrument_id = ?
              AND interval      = ?
              AND status        = 'IN_PROGRESS'
            """,
            instrumentId, interval
        );
    }

    /**
     * Returns count of FAILED chunks for this instrument+interval — useful for logging summary.
     */
    public int countFailed(long instrumentId, String interval) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT count()
            FROM trading.backfill_progress FINAL
            WHERE instrument_id = ?
              AND interval      = ?
              AND status        = 'FAILED'
            """,
            Integer.class,
            instrumentId, interval
        );
        return count != null ? count : 0;
    }

    public void markInProgress(long instrumentId, String interval, LocalDate from, LocalDate to) {
        upsert(instrumentId, interval, from, to, "IN_PROGRESS", 0);
    }

    public void markDone(long instrumentId, String interval, LocalDate from, LocalDate to, int rowsInserted) {
        upsert(instrumentId, interval, from, to, "DONE", rowsInserted);
    }

    public void markFailed(long instrumentId, String interval, LocalDate from, LocalDate to) {
        upsert(instrumentId, interval, from, to, "FAILED", 0);
    }

    private void upsert(long instrumentId, String interval,
                        LocalDate from, LocalDate to,
                        String status, int rows) {
        jdbcTemplate.update("""
            INSERT INTO trading.backfill_progress
                (instrument_id, interval, from_date, to_date, status, rows_inserted, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            """,
            instrumentId, interval,
            java.sql.Date.valueOf(from),
            java.sql.Date.valueOf(to),
            status, rows
        );
    }
}
