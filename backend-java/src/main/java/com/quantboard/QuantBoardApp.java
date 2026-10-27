package com.quantboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuantBoardApp {
    public static void main(String[] args) {
        SpringApplication.run(QuantBoardApp.class, args);
    }
}
