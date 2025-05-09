package com.fourj.orderservice.service;

import com.fourj.orderservice.dto.CreateOrderRequest;
import com.fourj.orderservice.dto.OrderDto;
import com.fourj.orderservice.dto.OrderStatisticsDto;
import com.fourj.orderservice.dto.UpdateOrderStatusRequest;
import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.payos.type.WebhookData;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface OrderService {
    OrderDto createOrder(String userId, String token, CreateOrderRequest request);
    OrderDto getOrderById(Long id);
    Order getOrderByOrderNumber(String orderNumber);
    OrderDto getOrderByNumber(String orderNumber);
    Page<OrderDto> getOrdersByUserId(String userId, Pageable pageable);
    Page<OrderDto> getOrdersByUserIdAndDateRange(String userId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);
    List<OrderDto> getOrdersByStatus(OrderStatus status);
    Page<OrderDto> getOrdersByStatus(OrderStatus status, Pageable pageable);
    OrderDto updateOrderStatus(Long id, UpdateOrderStatusRequest request);
    OrderDto cancelOrder(Long id);
    OrderDto createOrderFromEvent(String userId, CreateOrderRequest request);
    void updateOrder(WebhookData data);
    Page<OrderDto> getAllOrders(Pageable pageable);
    Map<String, Object> getOrderStatistics();
    Page<OrderDto> getOrdersBySellerId(String sellerId, Pageable pageable);
    Page<OrderDto> getOrdersBySellerIdAndStatus(String sellerId, OrderStatus status, Pageable pageable);
    Page<OrderDto> getOrdersBySellerIdAndDateRange(String sellerId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);
    OrderDto getOrderByIdAndSellerId(Long orderId, String sellerId);
    OrderStatisticsDto getSellerOrderStatistics(String sellerId);
    Page<OrderDto> getOrdersByDateRange(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);
    OrderStatisticsDto getAdminOrderStatistics();
    Map<String, Object> getDashboardStatistics(LocalDateTime startDate, LocalDateTime endDate);
}