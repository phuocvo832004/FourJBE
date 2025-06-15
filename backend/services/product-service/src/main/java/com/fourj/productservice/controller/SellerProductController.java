package com.fourj.productservice.controller;

import com.fourj.productservice.dto.ProductCreateDto;
import com.fourj.productservice.dto.ProductDto;
import com.fourj.productservice.dto.ProductUpdateDto;
import com.fourj.productservice.service.ProductService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products/seller")
@Slf4j
@PreAuthorize("hasAuthority('seller:access')")
public class SellerProductController {

    private final ProductService productService;

    @Autowired
    public SellerProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductDto> createProduct(@Valid @RequestBody ProductCreateDto productCreateDto, 
                                                  @AuthenticationPrincipal Jwt jwt) {
        String sellerId = jwt.getSubject();
        log.info("Seller {} tạo sản phẩm mới: {}", sellerId, productCreateDto.getName());
        
        // Gán sellerId vào sản phẩm
        productCreateDto.setSellerId(sellerId);
        
        return new ResponseEntity<>(productService.createProduct(productCreateDto), HttpStatus.CREATED);
    }

    @GetMapping("/my-products")
    public ResponseEntity<Page<ProductDto>> getSellerProducts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        String sellerId = jwt.getSubject();
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        return ResponseEntity.ok(productService.getProductsBySeller(sellerId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> getProductById(@PathVariable Long id, 
                                                   @AuthenticationPrincipal Jwt jwt) {
        String sellerId = jwt.getSubject();
        return ResponseEntity.ok(productService.getProductByIdAndSellerId(id, sellerId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductDto> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody ProductUpdateDto productUpdateDto,
            @AuthenticationPrincipal Jwt jwt) {

        String sellerId = jwt.getSubject();
        log.info("Seller {} cập nhật sản phẩm: {}", sellerId, id);
        
        // Kiểm tra quyền sở hữu sản phẩm
        productService.getProductByIdAndSellerId(id, sellerId);
        
        return ResponseEntity.ok(productService.updateProduct(id, productUpdateDto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id,
                                            @AuthenticationPrincipal Jwt jwt) {
        String sellerId = jwt.getSubject();
        log.info("Seller {} xóa sản phẩm: {}", sellerId, id);
        
        // Kiểm tra quyền sở hữu sản phẩm
        productService.getProductByIdAndSellerId(id, sellerId);
        
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public ResponseEntity<Page<ProductDto>> searchSellerProducts(
            @RequestParam String keyword,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        String sellerId = jwt.getSubject();
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        return ResponseEntity.ok(productService.searchProductsBySeller(sellerId, keyword, pageable));
    }
    
    @GetMapping("/category/{categoryId}")
    public ResponseEntity<Page<ProductDto>> getProductsByCategoryAndSeller(
            @PathVariable Long categoryId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        String sellerId = jwt.getSubject();
        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        return ResponseEntity.ok(productService.getProductsByCategoryAndSeller(categoryId, sellerId, pageable));
    }
} 