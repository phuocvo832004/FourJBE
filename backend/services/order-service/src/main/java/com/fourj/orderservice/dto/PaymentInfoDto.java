package com.fourj.orderservice.dto;

import com.fourj.orderservice.model.PaymentMethod;
import com.fourj.orderservice.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInfoDto {
    private Long id;
    private PaymentMethod paymentMethod;
    private PaymentStatus paymentStatus;
    private String transactionId;
    private String paymentLinkId;
    private String checkoutUrl; // Link thanh toán payOS
    private Long payOsOrderCode;
    private LocalDateTime paymentDate;
}