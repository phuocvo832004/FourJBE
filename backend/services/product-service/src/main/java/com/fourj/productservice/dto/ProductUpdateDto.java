package com.fourj.productservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductUpdateDto {
    private String name;
    private String description;

    @Positive(message = "Giá sản phẩm phải lớn hơn 0")
    private BigDecimal price;

    @PositiveOrZero(message = "Số lượng tồn kho phải lớn hơn hoặc bằng 0")
    private Integer stockQuantity;

    private String imageUrl;
    private Long categoryId;
    private List<ProductAttributeDto> attributes;
    private Boolean active;
}