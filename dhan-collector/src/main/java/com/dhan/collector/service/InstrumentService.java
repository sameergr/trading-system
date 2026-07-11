package com.dhan.collector.service;

import com.dhan.collector.model.Instrument;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class InstrumentService {

    private final JdbcTemplate jdbcTemplate;
    private final Resource instrumentsFile;
    private List<Instrument> instruments = new ArrayList<>();

    public InstrumentService(JdbcTemplate jdbcTemplate,
                              @Value("${backfill.instruments-file}") Resource instrumentsFile) {
        this.jdbcTemplate = jdbcTemplate;
        this.instrumentsFile = instrumentsFile;
    }

    @PostConstruct
    public void loadInstruments() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(instrumentsFile.getInputStream()))) {

            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; } // skip header
                String[] parts = line.split(",");
                if (parts.length < 4) continue;

                Instrument instrument = Instrument.builder()
                        .securityId(Long.parseLong(parts[0].trim()))
                        .symbol(parts[1].trim())
                        .exchangeSegment(parts[2].trim())
                        .instrumentType(parts[3].trim())
                        .build();
                instruments.add(instrument);
            }

            log.info("Loaded {} instruments from config", instruments.size());
            syncToClickHouse();

        } catch (Exception e) {
            log.error("Failed to load instruments file", e);
            throw new RuntimeException("Cannot start without instruments config", e);
        }
    }

    private void syncToClickHouse() {
        // Upsert all instruments into ClickHouse instruments table
        for (Instrument i : instruments) {
            jdbcTemplate.update("""
                INSERT INTO trading.instruments
                (security_id, symbol, exchange_segment, instrument_type)
                VALUES (?, ?, ?, ?)
                """,
                i.getSecurityId(),
                i.getSymbol(),
                i.getExchangeSegment(),
                i.getInstrumentType()
            );
        }
        log.info("Synced {} instruments to ClickHouse", instruments.size());
    }

    public List<Instrument> getAllInstruments() {
        return List.copyOf(instruments);
    }
}
