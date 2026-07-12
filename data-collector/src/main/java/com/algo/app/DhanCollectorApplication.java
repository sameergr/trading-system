package com.algo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DhanCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DhanCollectorApplication.class, args);
    }
}
