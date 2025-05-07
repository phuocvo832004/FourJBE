package com.fourj.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatisticsDto {
    private long totalOrders;
    private long pendingOrders;
    private long processingOrders;
    private long shippedOrders;
    private long deliveredOrders;
    private long completedOrders;
    private long cancelledOrders;
    private BigDecimal totalRevenue;
    private BigDecimal avgOrderValue;
    
    // Thống kê theo thời gian (hàng ngày, hàng tuần, hàng tháng)
    private Map<String, Long> orderCountByDay;
    private Map<String, BigDecimal> revenueByDay;
    
    // Tỷ lệ đơn hàng hoàn thành/hủy
    private double completionRate;
    private double cancellationRate;
} 