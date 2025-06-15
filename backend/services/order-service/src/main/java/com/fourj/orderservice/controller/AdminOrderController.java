package com.fourj.orderservice.controller;

import com.fourj.orderservice.dto.OrderDto;
import com.fourj.orderservice.dto.OrderStatisticsDto;
import com.fourj.orderservice.dto.UpdateOrderStatusRequest;
import com.fourj.orderservice.model.OrderStatus;
import com.fourj.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/orders/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:access')")
public class AdminOrderController {

    private final OrderService orderService;

    @GetMapping
    public ResponseEntity<Page<OrderDto>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) OrderStatus status) {
        
        log.info("Admin đang xem tất cả đơn hàng");
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        
        Pageable pageable = PageRequest.of(page, size, sort);
        
        if (status != null) {
            return ResponseEntity.ok(orderService.getOrdersByStatus(status, pageable));
        } else {
            return ResponseEntity.ok(orderService.getAllOrders(pageable));
        }
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrderById(@PathVariable Long id) {
        log.info("Admin đang xem đơn hàng {}", id);
        return ResponseEntity.ok(orderService.getOrderById(id));
    }
    
    @PutMapping("/{id}/status")
    public ResponseEntity<OrderDto> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        
        log.info("Admin đang cập nhật trạng thái đơn hàng {} thành {}", id, request.getStatus());
        return ResponseEntity.ok(orderService.updateOrderStatus(id, request));
    }
    
    @GetMapping("/by-date-range")
    public ResponseEntity<Page<OrderDto>> getOrdersByDateRange(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        LocalDateTime start = startDate != null ? 
                LocalDate.parse(startDate).atStartOfDay() : 
                LocalDate.now().minusMonths(1).atStartOfDay();
        
        LocalDateTime end = endDate != null ? 
                LocalDate.parse(endDate).atTime(LocalTime.MAX) : 
                LocalDate.now().atTime(LocalTime.MAX);
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        
        Pageable pageable = PageRequest.of(page, size, sort);
        
        return ResponseEntity.ok(orderService.getOrdersByDateRange(start, end, pageable));
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<OrderDto>> getOrdersByUser(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        log.info("Admin đang xem đơn hàng của người dùng {}", userId);
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        
        Pageable pageable = PageRequest.of(page, size, sort);
        
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId, pageable));
    }
    
    @GetMapping("/seller/{sellerId}")
    public ResponseEntity<Page<OrderDto>> getOrdersBySeller(
            @PathVariable String sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) OrderStatus status) {
        
        log.info("Admin đang xem đơn hàng của seller {}", sellerId);
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        
        Pageable pageable = PageRequest.of(page, size, sort);
        
        if (status != null) {
            return ResponseEntity.ok(orderService.getOrdersBySellerIdAndStatus(sellerId, status, pageable));
        } else {
            return ResponseEntity.ok(orderService.getOrdersBySellerId(sellerId, pageable));
        }
    }
    
    @GetMapping("/stats")
    public ResponseEntity<OrderStatisticsDto> getOrderStats() {
        log.info("Admin đang xem thống kê đơn hàng");
        
        OrderStatisticsDto stats = orderService.getAdminOrderStatistics();
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboardStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        
        LocalDateTime start = startDate != null ? 
                LocalDate.parse(startDate).atStartOfDay() : 
                LocalDate.now().minusMonths(1).atStartOfDay();
        
        LocalDateTime end = endDate != null ? 
                LocalDate.parse(endDate).atTime(LocalTime.MAX) : 
                LocalDate.now().atTime(LocalTime.MAX);
        
        Map<String, Object> dashboardStats = orderService.getDashboardStatistics(start, end);
        return ResponseEntity.ok(dashboardStats);
    }
} 