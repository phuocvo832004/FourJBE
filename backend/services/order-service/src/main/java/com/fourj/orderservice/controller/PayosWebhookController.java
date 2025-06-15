package com.fourj.orderservice.controller;

import com.fourj.orderservice.OrderServiceApplication;
import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.repository.OrderRepository;
import com.fourj.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.payos.PayOS;
import vn.payos.type.Webhook;
import vn.payos.type.WebhookData;

@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PayosWebhookController {

    private final PayOS payOS; // Được khởi tạo ở cấu hình
    private final OrderService orderService;

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody Webhook webhookBody) {
        log.info("Nhận webhook từ PayOS: {}", webhookBody);
        try {
            // Bổ sung try-catch chi tiết cho từng bước
            WebhookData data;
            try {
                data = payOS.verifyPaymentWebhookData(webhookBody);
            } catch (Exception e) {
                log.error("❌ Lỗi khi xác thực webhook với PayOS: {}", e.getMessage(), e);
                // Vẫn trả về 200 để PayOS không tiếp tục gửi lại
                return ResponseEntity.ok("Webhook signature verification failed");
            }

            log.info("Webhook data: {}", data);

            try {
                orderService.updateOrder(data);
            } catch (Exception e) {
                log.error("❌ Lỗi khi cập nhật đơn hàng từ webhook: {}", e.getMessage(), e);
                // Vẫn trả về 200 để PayOS không tiếp tục gửi lại
                return ResponseEntity.ok("Order update failed but webhook received");
            }

            return ResponseEntity.ok("Webhook processed successfully");
        } catch (Exception e) {
            log.error("❌ Lỗi tổng thể khi xử lý webhook: {}", e.getMessage(), e);
            // Trả về 200 để tránh PayOS gửi lại liên tục
            return ResponseEntity.ok("Webhook received with errors");
        }
    }

    public String toString(WebhookData webhookData) {
        return "WebhookData(" + "orderCode=" + webhookData.getOrderCode() + ", amount=" + webhookData.getAmount() + ", description=" + webhookData.getDescription() + ", accountNumber=" + webhookData.getAccountNumber() + ", reference=" + webhookData.getReference() + ", transactionDateTime=" + webhookData.getTransactionDateTime() + ", currency=" + webhookData.getCurrency() + ", paymentLinkId=" + webhookData.getPaymentLinkId() + ", code=" + webhookData.getCode() + ", desc=" + webhookData.getDesc() + ", counterAccountBankId=" + webhookData.getCounterAccountBankId() + ", counterAccountBankName=" + webhookData.getCounterAccountBankName() + ", counterAccountName=" + webhookData.getCounterAccountName() + ", counterAccountNumber=" + webhookData.getCounterAccountNumber() + ", virtualAccountName=" + webhookData.getVirtualAccountName() + ", virtualAccountNumber=" + webhookData.getVirtualAccountNumber() + ")";
    }
}
