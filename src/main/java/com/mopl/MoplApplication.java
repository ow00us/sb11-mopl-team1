package com.mopl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MoplApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoplApplication.class, args);
    }
}
