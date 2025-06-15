package com.fourj.orderservice.dto;

import com.fourj.orderservice.model.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDto {
    private Long id;
    private String orderNumber;
    private String userId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private List<OrderItemDto> items;
    private ShippingAddressDto shippingAddress;
    private PaymentInfoDto paymentInfo;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}