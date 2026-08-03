package com.fraudengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudEngineApplication.class, args);
    }
}
