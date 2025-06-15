package com.fourj.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileUpdateDto {
    private String fullName;
    private String phone;
    private String fullAddress;

    @Past(message = "Ngày sinh phải là ngày trong quá khứ")
    private LocalDate dateOfBirth;

    private String gender;
    private String avatarUrl;
    private String biography;
    
    // Thông tin seller
    private String storeName;
    private String taxId;
    private String businessAddress;
}