package com.settlementmatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SettlementMatcherApplication {

	public static void main(String[] args) {
		SpringApplication.run(SettlementMatcherApplication.class, args);
	}

}
