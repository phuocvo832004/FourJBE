package com.fourj.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartItemDto implements Serializable {
    private Long id;
    private Long productId;
    private String productName;
    private String productImage;
    private double price;
    private int quantity;
}