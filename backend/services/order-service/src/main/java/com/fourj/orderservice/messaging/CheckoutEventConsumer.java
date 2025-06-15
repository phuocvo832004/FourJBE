package com.fourj.orderservice.messaging;

import com.fourj.orderservice.dto.CheckoutEventDto;
import com.fourj.orderservice.dto.CreateOrderRequest;
import com.fourj.orderservice.dto.OrderItemDto;
import com.fourj.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CheckoutEventConsumer {

    private final OrderService orderService;

    @RabbitListener(queues = "checkout-queue")
    public void handleCheckoutEvent(CheckoutEventDto event) {
        log.info("Received checkout event for userId: {}", event.getUserId());

        try {
            List<OrderItemDto> orderItems = new ArrayList<>();

            if (event.getItems() != null) {
                orderItems = event.getItems().stream()
                        .map(item -> {
                            OrderItemDto orderItem = new OrderItemDto();
                            orderItem.setProductId(item.getProductId());
                            orderItem.setProductName(item.getProductName());
                            orderItem.setPrice(BigDecimal.valueOf(item.getPrice()));
                            orderItem.setQuantity(item.getQuantity());
                            return orderItem;
                        })
                        .toList();
            }

            // Tạo CreateOrderRequest từ CheckoutEventDto
            CreateOrderRequest request = new CreateOrderRequest();
            request.setItems(orderItems);
            request.setShippingAddress(event.getShippingAddress());
            request.setPaymentMethod(event.getPaymentMethod());

            // Tạo đơn hàng
            orderService.createOrderFromEvent(event.getUserId(), request);
            log.info("Order created successfully for userId: {}", event.getUserId());
        } catch (Exception e) {
            log.error("Failed to create order for userId: {}", event.getUserId(), e);
            // Có thể implement retry logic hoặc gửi message vào dead-letter queue
        }
    }
}