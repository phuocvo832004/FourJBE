package com.fourj.productservice.event.dto;

import com.fourj.productservice.dto.ProductDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO đại diện cho sự kiện liên quan đến sản phẩm
 * Được sử dụng để gửi sự kiện tới Kafka
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductEventDto {
    private String eventType;
    private String productId;
    private ProductDto payload;
}
