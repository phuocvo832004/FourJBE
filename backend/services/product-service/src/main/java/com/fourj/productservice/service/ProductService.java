package com.fourj.productservice.service;

import com.fourj.productservice.dto.ProductCreateDto;
import com.fourj.productservice.dto.ProductDto;
import com.fourj.productservice.dto.ProductUpdateDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductService {
    ProductDto createProduct(ProductCreateDto productCreateDto);
    ProductDto getProductById(Long id);
    Page<ProductDto> getAllProducts(Pageable pageable);
    Page<ProductDto> getProductsByCategory(Long categoryId, Pageable pageable);
    Page<ProductDto> searchProducts(String keyword, Pageable pageable);
    ProductDto updateProduct(Long id, ProductUpdateDto productUpdateDto);
    void deleteProduct(Long id);
    
    // Các phương thức mới dành cho seller
    Page<ProductDto> getProductsBySeller(String sellerId, Pageable pageable);
    Page<ProductDto> searchProductsBySeller(String sellerId, String keyword, Pageable pageable);
    ProductDto getProductByIdAndSellerId(Long id, String sellerId);
    Page<ProductDto> getProductsByCategoryAndSeller(Long categoryId, String sellerId, Pageable pageable);
    
    // Các phương thức mới dành cho admin
    Page<ProductDto> getProductsByActiveStatus(boolean active, Pageable pageable);
    Page<ProductDto> getAllProductsIncludeInactive(Pageable pageable);
    
    // Phương thức giảm số lượng tồn kho sau khi thanh toán
    boolean updateStockQuantity(Long productId, int quantity);
}