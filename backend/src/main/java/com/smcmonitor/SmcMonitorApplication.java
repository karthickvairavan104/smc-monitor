package com.smcmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmcMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmcMonitorApplication.class, args);
    }
}
