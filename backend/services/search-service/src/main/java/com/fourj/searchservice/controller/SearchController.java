package com.fourj.searchservice.controller;

import com.fourj.searchservice.document.ProductDocument;
import com.fourj.searchservice.dto.SearchRequest;
import com.fourj.searchservice.dto.SearchResponse;
import com.fourj.searchservice.dto.ProductIndexDto;
import com.fourj.searchservice.service.SearchService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Slf4j
public class SearchController {

    private final SearchService searchService;
    
    /**
     * Tìm kiếm nâng cao với nhiều tùy chọn lọc và sắp xếp
     */
    @PostMapping("/products")
    @Timed(value = "search.request", description = "Time taken to process search requests")
    public ResponseEntity<SearchResponse> searchProducts(
            @RequestBody @Validated SearchRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        
        try {
            // Thêm userId vào request nếu có (cho personalization)
            if (userId != null) {
                request.setUserId(userId);
            }
            
            // Đảm bảo các giá trị mặc định hợp lý
            if (request.getFrom() < 0) {
                request.setFrom(0);
            }
            
            if (request.getSize() <= 0 || request.getSize() > 100) {
                request.setSize(20); // Giới hạn size mặc định
            }
            
            log.debug("Search request: query={}, userId={}", request.getQuery(), userId);
            SearchResponse response = searchService.searchProducts(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during search", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * API đơn giản hóa để tìm kiếm sản phẩm, tương thích với API mặc định của product-service
     */
    @GetMapping("/products")
    @Timed(value = "search.simple", description = "Time taken to process simple search requests")
    public ResponseEntity<SearchResponse> simpleSearch(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        
        try {
            // Chuyển đổi tham số thành SearchRequest
            SearchRequest.SortOption sortOption = determineSortOption(sortBy, sortDir);
            
            SearchRequest request = SearchRequest.builder()
                    .query(keyword)
                    .from(page * size)
                    .size(size)
                    .categories(categories)
                    .sortOption(sortOption)
                    .includeAggregations(true)
                    .build();
            
            SearchResponse response = searchService.searchProducts(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during simple search", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Tìm kiếm sản phẩm theo khoảng giá
     */
    @GetMapping("/products/price-range")
    public ResponseEntity<SearchResponse> searchByPriceRange(
            @RequestParam(required = false) String keyword,
            @RequestParam BigDecimal minPrice,
            @RequestParam BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(keyword)
                    .from(page * size)
                    .size(size)
                    .priceRange(SearchRequest.PriceRange.builder()
                            .min(minPrice)
                            .max(maxPrice)
                            .build())
                    .sortOption(SearchRequest.SortOption.PRICE_ASC)
                    .includeAggregations(true)
                    .build();
            
            SearchResponse response = searchService.searchProducts(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during price range search", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Tìm kiếm sản phẩm theo danh mục
     */
    @GetMapping("/products/category/{categoryId}")
    public ResponseEntity<SearchResponse> searchByCategory(
            @PathVariable String categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "price") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        
        try {
            SearchRequest.SortOption sortOption = determineSortOption(sortBy, sortDir);
            
            SearchRequest request = SearchRequest.builder()
                    .query(keyword)
                    .from(page * size)
                    .size(size)
                    .categories(List.of(categoryId))
                    .sortOption(sortOption)
                    .includeAggregations(true)
                    .build();
            
            SearchResponse response = searchService.searchProducts(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during category search", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * API tìm kiếm theo thuộc tính sản phẩm
     */
    @GetMapping("/products/filter")
    public ResponseEntity<SearchResponse> filterProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Map<String, List<String>> attributes) {
        
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(keyword)
                    .from(page * size)
                    .size(size)
                    .attributes(attributes)
                    .includeAggregations(true)
                    .build();
            
            SearchResponse response = searchService.searchProducts(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during filter search", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Gợi ý tìm kiếm
     */
    @GetMapping("/suggestions")
    @Timed(value = "search.suggestions", description = "Time taken to get search suggestions")
    public ResponseEntity<List<String>> getSuggestions(
            @RequestParam String prefix,
            @RequestParam(defaultValue = "5") int size) {
        
        if (size <= 0 || size > 20) {
            size = 5; // Giới hạn size mặc định cho suggestions
        }
        
        List<String> suggestions = searchService.getSuggestions(prefix, size);
        return ResponseEntity.ok(suggestions);
    }
    
    /**
     * Xác định tùy chọn sắp xếp dựa trên tham số
     */
    private SearchRequest.SortOption determineSortOption(String sortBy, String sortDir) {
        if (sortBy == null) {
            return SearchRequest.SortOption.RELEVANCE;
        }

        return switch (sortBy.toLowerCase()) {
            case "price" -> "asc".equalsIgnoreCase(sortDir)
                    ? SearchRequest.SortOption.PRICE_ASC
                    : SearchRequest.SortOption.PRICE_DESC;
            case "created_at", "createdat", "date" -> SearchRequest.SortOption.NEWEST;
            case "sold_count", "soldcount", "sales" -> SearchRequest.SortOption.BEST_SELLING;
            case "rating" -> SearchRequest.SortOption.HIGHEST_RATED;
            default -> SearchRequest.SortOption.RELEVANCE;
        };
    }
    
    /**
     * API để kiểm tra trạng thái của index
     */
    @GetMapping("/index-status")
    public ResponseEntity<Map<String, Object>> getIndexStatus() {
        try {
            Map<String, Object> status = searchService.getIndexStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Error getting index status", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * API để xóa và tạo lại index
     */
    @PostMapping("/recreate-index")
    public ResponseEntity<String> recreateIndex() {
        try {
            boolean success = searchService.recreateIndex();
            if (success) {
                return ResponseEntity.ok("Index recreated successfully");
            } else {
                return ResponseEntity.internalServerError().body("Failed to recreate index");
            }
        } catch (Exception e) {
            log.error("Error recreating index", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
    
    /**
     * API để thêm sản phẩm vào index
     */
    @PostMapping("/index-product")
    public ResponseEntity<String> indexProduct(@RequestBody ProductIndexDto product) {
        try {
            boolean success = searchService.indexProduct(product);
            if (success) {
                return ResponseEntity.ok("Product indexed successfully");
            } else {
                return ResponseEntity.badRequest().body("Failed to index product");
            }
        } catch (Exception e) {
            log.error("Error indexing product", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
    
    /**
     * API để thêm nhiều sản phẩm vào index (bulk)
     */
    @PostMapping("/bulk-index-products")
    public ResponseEntity<String> bulkIndexProducts(@RequestBody List<ProductIndexDto> products) {
        try {
            if (products == null || products.isEmpty()) {
                return ResponseEntity.badRequest().body("No products provided");
            }
            
            int count = searchService.bulkIndexProducts(products);
            return ResponseEntity.ok("Indexed " + count + "/" + products.size() + " products successfully");
        } catch (Exception e) {
            log.error("Error bulk indexing products", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
    
    /**
     * API kiểm tra trạng thái hoạt động cơ bản
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", System.currentTimeMillis());
        status.put("service", "search-service");
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * API để xóa sản phẩm khỏi index
     */
    @DeleteMapping("/product/{id}")
    public ResponseEntity<String> deleteProduct(@PathVariable String id) {
        try {
            boolean success = searchService.deleteProduct(id);
            if (success) {
                return ResponseEntity.ok("Product deleted successfully");
            } else {
                return ResponseEntity.badRequest().body("Failed to delete product");
            }
        } catch (Exception e) {
            log.error("Error deleting product", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
} 