package com.fourj.orderservice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import vn.payos.PayOS;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class PayOSConfig {
    @Value("${PAYOS_CLIENT_ID}")
    private String clientId;

    @Value("${PAYOS_API_KEY}")
    private String apiKey;

    @Value("${PAYOS_CHECKSUM_KEY}")
    private String checksumKey;

    @Bean
    public PayOS payOS() {
        return new PayOS(clientId, apiKey, checksumKey);
    }

//    @PostConstruct
//    public void setupWebhook() throws Exception {
//        // Initialize payOS locally since we can't inject it (would cause circular dependency)
//        this.payOS = new PayOS(clientId, apiKey, checksumKey);
//
//        String webhookUrl = "https://your-site.com/api/orders/webhook";
//        String verifiedWebhookUrl = payOS.confirmWebhook(webhookUrl);
//        log.info("Webhook URL verified: {}", verifiedWebhookUrl);
//    }
}