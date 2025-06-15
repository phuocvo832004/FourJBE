package com.fourj.orderservice.config;


import com.fourj.orderservice.service.OrderService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.payos.PayOS;

@Component
@RequiredArgsConstructor
public class PayOSWebhookInitializer {

    private final PayOS payOS;

    @Value("${PAYOS_WebHook}")
    private  String webhookUrl;
    @PostConstruct
    public void initWebhook() {
        try {
            String verifiedUrl = payOS.confirmWebhook(webhookUrl);
            System.out.println("✅ Webhook đã được xác thực và đăng ký với PayOS: " + verifiedUrl);
        } catch (Exception e) {
            System.err.println("❌ Lỗi khi xác thực webhook với PayOS: " + e.getMessage());
        }
    }
}

