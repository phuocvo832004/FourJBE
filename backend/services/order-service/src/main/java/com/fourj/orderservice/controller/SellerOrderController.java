package com.fourj.orderservice.controller;

import com.fourj.orderservice.dto.OrderDto;
import com.fourj.orderservice.dto.OrderStatisticsDto;
import com.fourj.orderservice.dto.UpdateOrderStatusRequest;
import com.fourj.orderservice.exception.UnauthorizedAccessException;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/orders/seller")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('seller:access')")
public class SellerOrderController {

    private final OrderService orderService;

    @GetMapping
    public ResponseEntity<Page<OrderDto>> getSellerOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) OrderStatus status) {
        
        String sellerId = jwt.getSubject();
        log.info("Seller {} đang xem đơn hàng", sellerId);
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        
        Pageable pageable = PageRequest.of(page, size, sort);
        
        if (status != null) {
            return ResponseEntity.ok(orderService.getOrdersBySellerIdAndStatus(sellerId, status, pageable));
        } else {
            return ResponseEntity.ok(orderService.getOrdersBySellerId(sellerId, pageable));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> getOrderById(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        
        String sellerId = jwt.getSubject();
        log.info("Seller {} đang xem đơn hàng {}", sellerId, id);
        
        return ResponseEntity.ok(orderService.getOrderByIdAndSellerId(id, sellerId));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<OrderDto> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        
        String sellerId = jwt.getSubject();
        log.info("Seller {} đang cập nhật trạng thái đơn hàng {} thành {}", 
                sellerId, id, request.getStatus());
        
        // Kiểm tra quyền truy cập đơn hàng
        orderService.getOrderByIdAndSellerId(id, sellerId);
        
        // Hạn chế seller chỉ được cập nhật một số trạng thái nhất định
        if (request.getStatus() == OrderStatus.CANCELLED ||
            request.getStatus() == OrderStatus.COMPLETED) {
            throw new UnauthorizedAccessException("Seller không có quyền cập nhật đơn hàng sang trạng thái này");
        }
        
        return ResponseEntity.ok(orderService.updateOrderStatus(id, request));
    }

    @GetMapping("/by-date-range")
    public ResponseEntity<Page<OrderDto>> getOrdersByDateRange(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        String sellerId = jwt.getSubject();
        
        LocalDateTime start = startDate != null ? 
                LocalDate.parse(startDate).atStartOfDay() : 
                LocalDate.now().minusMonths(1).atStartOfDay();
        
        LocalDateTime end = endDate != null ? 
                LocalDate.parse(endDate).atTime(LocalTime.MAX) : 
                LocalDate.now().atTime(LocalTime.MAX);
        
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        
        Pageable pageable = PageRequest.of(page, size, sort);
        
        return ResponseEntity.ok(orderService.getOrdersBySellerIdAndDateRange(sellerId, start, end, pageable));
    }

    @GetMapping("/stats")
    public ResponseEntity<OrderStatisticsDto> getOrderStats(@AuthenticationPrincipal Jwt jwt) {
        String sellerId = jwt.getSubject();
        log.info("Seller {} đang xem thống kê đơn hàng", sellerId);
        
        OrderStatisticsDto stats = orderService.getSellerOrderStatistics(sellerId);
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboardStats(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        
        String sellerId = jwt.getSubject();
        
        LocalDateTime start = startDate != null ? 
                LocalDate.parse(startDate).atStartOfDay() : 
                LocalDate.now().minusMonths(1).atStartOfDay();
        
        LocalDateTime end = endDate != null ? 
                LocalDate.parse(endDate).atTime(LocalTime.MAX) : 
                LocalDate.now().atTime(LocalTime.MAX);
        
        // Cần thực hiện thêm logic trong service
        Map<String, Object> dashboardStats = orderService.getDashboardStatistics(start, end);
        return ResponseEntity.ok(dashboardStats);
    }
} 