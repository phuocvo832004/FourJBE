package com.fourj.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ShippingAddressDto {
    private String address;

    public ShippingAddressDto(String address) {
        this.address = address;
    }
}