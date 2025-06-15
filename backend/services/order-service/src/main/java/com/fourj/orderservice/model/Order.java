package com.fourj.orderservice.model;

import com.fourj.orderservice.util.DateTimeUtil;
import jakarta.persistence.*;
import jakarta.persistence.Index;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_order_is_uploaded", columnList = "isUploadedToAzure"),
    @Index(name = "idx_order_status", columnList = "status"),
    @Index(name = "idx_order_created_at", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "shipping_address_id")
    private ShippingAddress shippingAddress;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "payment_info_id")
    private PaymentInfo paymentInfo;

    private String notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean isUploadedToAzure = false;

    @PrePersist
    protected void onCreate() {
        createdAt = DateTimeUtil.nowInVietnam();
        updatedAt = DateTimeUtil.nowInVietnam();
        if (status == null) {
            status = OrderStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = DateTimeUtil.nowInVietnam();
        if (status == OrderStatus.COMPLETED && completedAt == null) {
            completedAt = DateTimeUtil.nowInVietnam();
        }
    }
}