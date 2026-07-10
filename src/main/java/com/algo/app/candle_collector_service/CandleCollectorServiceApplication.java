package com.algo.app.candle_collector_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CandleCollectorServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CandleCollectorServiceApplication.class, args);
	}

}
