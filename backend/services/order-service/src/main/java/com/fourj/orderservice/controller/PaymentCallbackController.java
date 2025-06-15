package com.fourj.orderservice.controller;

import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.model.OrderStatus;
import com.fourj.orderservice.model.PaymentStatus;
import com.fourj.orderservice.repository.OrderRepository;
import com.fourj.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentCallbackController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private static final String RESULT_URL = "http://localhost:5173/payment-result";

    @Transactional
    @GetMapping("/checkout/orders/cancel")
    public RedirectView handleCancelPayment(
            @RequestParam("orderId") Long orderId,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "cancel", required = false) Boolean cancel,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "orderCode", required = false) String orderCode) {
        
        log.info("### CALLBACK HỦY THANH TOÁN: orderId={}, code={}, id={}, cancel={}, status={}, orderCode={}", 
                orderId, code, id, cancel, status, orderCode);
        
        try {
            // Tìm đơn hàng theo ID
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
            
            log.info("### TRẠNG THÁI HIỆN TẠI: orderNumber={}, status={}, paymentStatus={}, paymentMethod={}", 
                     order.getOrderNumber(), order.getStatus(), 
                     order.getPaymentInfo().getPaymentStatus(), 
                     order.getPaymentInfo().getPaymentMethod());
            
            // Cập nhật trạng thái đơn hàng
            OrderStatus oldStatus = order.getStatus();
            PaymentStatus oldPaymentStatus = order.getPaymentInfo().getPaymentStatus();
            
            order.setStatus(OrderStatus.CANCELLED);
            order.getPaymentInfo().setPaymentStatus(PaymentStatus.CANCELLED);
            
            // Lưu đơn hàng
            Order savedOrder = orderRepository.save(order);
            
            log.info("### ĐÃ CẬP NHẬT: orderNumber={}, từ status={} thành {}, thanh toán từ {} thành {}", 
                    savedOrder.getOrderNumber(), oldStatus, savedOrder.getStatus(), 
                    oldPaymentStatus, savedOrder.getPaymentInfo().getPaymentStatus());
            
            // Chuyển hướng về trang frontend để hiển thị thông báo
            return new RedirectView(RESULT_URL + "?status=cancelled&orderCode=" + encodeParam(order.getOrderNumber()));
        } catch (Exception e) {
            log.error("### LỖI XỬ LÝ HỦY THANH TOÁN: {}", e.getMessage(), e);
            return new RedirectView(RESULT_URL + "?status=error&message=" + encodeParam(e.getMessage()));
        }
    }
    
    @GetMapping("/checkout/orders/success")
    public RedirectView handleSuccessPayment(
            @RequestParam("orderId") Long orderId,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "orderCode", required = false) String orderCode) {
        
        log.info("### CALLBACK THANH TOÁN THÀNH CÔNG: orderId={}, code={}, id={}, status={}, orderCode={}", 
                orderId, code, id, status, orderCode);
                
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng: " + orderId));
                    
            log.info("### CHỈ CHUYỂN HƯỚNG: đơn hàng {} hiện có trạng thái {}, trạng thái thanh toán {}", 
                     orderId, order.getStatus(), order.getPaymentInfo().getPaymentStatus());
                     
            return new RedirectView(RESULT_URL + "?status=success&orderCode=" + encodeParam(order.getOrderNumber()));
        } catch (Exception e) {
            log.error("### LỖI KHI TÌM ĐƠN HÀNG: {}", e.getMessage());
            return new RedirectView(RESULT_URL + "?status=error&message=" + encodeParam(e.getMessage()));
        }
    }
    
    /**
     * Mã hóa tham số URL để tránh các ký tự đặc biệt gây lỗi
     */
    private String encodeParam(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            log.error("Lỗi khi mã hóa tham số URL: {}", e.getMessage());
            return value;
        }
    }
} 