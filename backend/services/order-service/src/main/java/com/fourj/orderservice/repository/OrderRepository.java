package com.fourj.orderservice.repository;

import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUserId(String userId, Pageable pageable);
    
    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.createdAt BETWEEN :startDate AND :endDate")
    Page<Order> findByUserIdAndCreatedAtBetween(
            @Param("userId") String userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);
    
    Optional<Order> findByOrderNumber(String orderNumber);
    List<Order> findByStatus(OrderStatus status);
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
    Optional<Order> findByPaymentInfoPayOsOrderCode(Long payOsOrderCode);
    
    // Tìm các đơn hàng có chứa sản phẩm của seller
    @Query("SELECT DISTINCT o FROM Order o JOIN o.items i WHERE i.sellerId = :sellerId")
    Page<Order> findBySellerId(@Param("sellerId") String sellerId, Pageable pageable);
    
    // Tìm các đơn hàng có chứa sản phẩm của seller và có trạng thái cụ thể
    @Query("SELECT DISTINCT o FROM Order o JOIN o.items i WHERE i.sellerId = :sellerId AND o.status = :status")
    Page<Order> findBySellerIdAndStatus(
            @Param("sellerId") String sellerId, 
            @Param("status") OrderStatus status, 
            Pageable pageable);
    
    // Tìm các đơn hàng có chứa sản phẩm của seller và trong khoảng thời gian
    @Query("SELECT DISTINCT o FROM Order o JOIN o.items i WHERE i.sellerId = :sellerId AND o.createdAt BETWEEN :startDate AND :endDate")
    Page<Order> findBySellerIdAndCreatedAtBetween(
            @Param("sellerId") String sellerId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);
    
    // Tìm kiếm đơn hàng theo khoảng thời gian
    @Query("SELECT o FROM Order o WHERE o.createdAt BETWEEN :startDate AND :endDate")
    Page<Order> findByCreatedAtBetween(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);
    
    // Kiểm tra đơn hàng có chứa sản phẩm của seller không
    @Query("SELECT COUNT(o) > 0 FROM Order o JOIN o.items i WHERE o.id = :orderId AND i.sellerId = :sellerId")
    boolean existsByOrderIdAndSellerId(@Param("orderId") Long orderId, @Param("sellerId") String sellerId);
    
    // Đếm số đơn hàng theo trạng thái
    Long countByStatus(OrderStatus status);
    
    // Đếm số đơn hàng của seller theo trạng thái
    @Query("SELECT COUNT(DISTINCT o) FROM Order o JOIN o.items i WHERE i.sellerId = :sellerId AND o.status = :status")
    Long countBySellerIdAndStatus(@Param("sellerId") String sellerId, @Param("status") OrderStatus status);

    List<Order> findByIsUploadedToAzureFalse();
    
    // Tìm đơn hàng chưa upload với giới hạn số lượng và sắp xếp theo thời gian tạo
    @Query(value = "SELECT o FROM Order o WHERE o.isUploadedToAzure = false ORDER BY o.createdAt ASC")
    List<Order> findOrdersForExport(Pageable pageable);
    
    // Tìm đơn hàng chưa upload trong khoảng thời gian
    @Query(value = "SELECT o FROM Order o WHERE o.isUploadedToAzure = false AND o.createdAt BETWEEN :startDate AND :endDate ORDER BY o.createdAt ASC")
    List<Order> findOrdersForExportInDateRange(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    @Modifying
    @Query("UPDATE Order o SET o.isUploadedToAzure = true WHERE o.id IN :orderIds")
    void markOrdersAsUploaded(@Param("orderIds") List<Long> orderIds);
}