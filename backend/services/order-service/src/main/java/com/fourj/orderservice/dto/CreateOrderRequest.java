package com.fourj.orderservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CreateOrderRequest {
    @NotBlank(message = "Địa chỉ giao hàng không được để trống")
    private String shippingAddress;

    @NotNull(message = "Các sản phẩm không được để trống")
    private List<OrderItemDto> items;

    @NotBlank(message = "Phương thức thanh toán không được để trống")
    private String paymentMethod;

    private String notes;
}