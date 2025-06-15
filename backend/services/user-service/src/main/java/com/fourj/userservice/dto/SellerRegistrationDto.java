package com.fourj.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SellerRegistrationDto {
    @NotBlank(message = "Tên cửa hàng không được để trống")
    private String storeName;
    
    @NotBlank(message = "Mã số thuế không được để trống")
    private String taxId;
    
    private String businessAddress;
    
    private String businessDescription;
} 