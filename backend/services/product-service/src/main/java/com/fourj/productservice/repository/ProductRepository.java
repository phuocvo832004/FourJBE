package com.fourj.productservice.repository;

import com.fourj.productservice.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByActiveTrue(Pageable pageable);
    Page<Product> findByCategoryIdAndActiveTrue(Long categoryId, Pageable pageable);
    Page<Product> findByNameContainingIgnoreCaseAndActiveTrue(String name, Pageable pageable);
    
    // Thêm các phương thức mới để tìm theo sellerId
    Page<Product> findBySellerIdAndActiveTrue(String sellerId, Pageable pageable);
    Page<Product> findBySellerIdAndNameContainingIgnoreCaseAndActiveTrue(String sellerId, String name, Pageable pageable);
    boolean existsByIdAndSellerId(Long id, String sellerId);
    Page<Product> findByCategoryIdAndSellerIdAndActiveTrue(Long categoryId, String sellerId, Pageable pageable);
    
    // Phương thức tìm theo trạng thái kích hoạt
    Page<Product> findByActive(boolean active, Pageable pageable);
    
    // Phương thức tìm tất cả sản phẩm cả kích hoạt và không kích hoạt
    Page<Product> findAll(Pageable pageable);
}