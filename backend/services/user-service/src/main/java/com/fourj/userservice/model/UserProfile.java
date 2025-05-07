package com.fourj.userservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String auth0Id;

    @Column(nullable = false)
    private String email;

    private String fullName;

    private String phone;

    @Column(length = 500)
    private String fullAddress;

    private LocalDate dateOfBirth;

    private String gender;

    private String avatarUrl;

    @Column(length = 1000)
    private String biography;

    // Thông tin bổ sung cho người bán
    @Column(name = "is_seller")
    private boolean seller = false;

    @Column(name = "store_name")
    private String storeName;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "business_address", length = 500)
    private String businessAddress;

    @Column(name = "verification_status")
    @Enumerated(EnumType.STRING)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "verification_date")
    private LocalDateTime verificationDate;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Enum cho trạng thái xác minh người bán
    public enum VerificationStatus {
        UNVERIFIED,       // Chưa được xác minh
        PENDING,          // Đang chờ xác minh
        VERIFIED,         // Đã xác minh
        REJECTED          // Bị từ chối
    }
}