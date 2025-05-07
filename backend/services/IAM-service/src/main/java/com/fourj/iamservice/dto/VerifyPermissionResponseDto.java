package com.fourj.iamservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyPermissionResponseDto {
    private boolean allowed;
    private String message;

    public VerifyPermissionResponseDto(boolean hasAllPermissions) {
    }
}