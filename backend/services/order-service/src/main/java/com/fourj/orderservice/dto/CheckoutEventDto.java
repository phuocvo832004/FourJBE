package com.fourj.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutEventDto implements Serializable {
    private String userId;
    private List<CartItemDto> items;
    private double totalAmount;
    private String shippingAddress;
    private String paymentMethod;
}
