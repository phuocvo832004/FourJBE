package com.fourj.iamservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserUpdateDto {
    private String fullName;
    private String phone;
    private String address;
    private String avatarUrl;
    private Boolean active;
}