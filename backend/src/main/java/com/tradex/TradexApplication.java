package com.tradex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TradeX - Virtual (paper) trading platform.
 *
 * <p><strong>This application never connects to a real brokerage and never places real
 * orders.</strong> All trades are simulated against virtual cash.</p>
 */
@SpringBootApplication(scanBasePackages = "com.tradex")
@ConfigurationPropertiesScan(basePackages = "com.tradex.config")
@EnableScheduling
@EnableJpaAuditing
public class TradexApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradexApplication.class, args);
    }
}
