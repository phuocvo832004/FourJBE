package com.fourj.orderservice.dto;

import com.fourj.orderservice.model.OrderStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateOrderStatusRequest {
    
    @NotNull(message = "Trạng thái đơn hàng không được để trống")
    private OrderStatus status;
    
    private String note;
}