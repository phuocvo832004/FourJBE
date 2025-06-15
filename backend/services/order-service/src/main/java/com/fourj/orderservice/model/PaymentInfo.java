package com.fourj.orderservice.model;

import com.fourj.orderservice.util.DateTimeUtil;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_info")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus paymentStatus;

    private String transactionId;
    private String paymentLinkId; // Thêm cho payOS
    private String checkoutUrl;   // Thêm cho payOS
    private Long payOsOrderCode;  // Thêm cho payOS

    private LocalDateTime paymentDate;

    @PrePersist
    protected void onCreate() {
        if (paymentStatus == null) {
            paymentStatus = PaymentStatus.PENDING;
        }
    }
    
    // Cập nhật paymentDate khi ghi nhận thanh toán thành công
    public void setPaymentComplete() {
        this.paymentStatus = PaymentStatus.COMPLETED;
        this.paymentDate = DateTimeUtil.nowInVietnam();
    }
}