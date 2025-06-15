package com.fourj.searchservice.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.fourj.searchservice.config.ElasticsearchConfig;
import com.fourj.searchservice.document.ProductDocument;
import com.fourj.searchservice.exception.ElasticsearchException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductIndexingService {

    private final ElasticsearchClient client;
    private final ElasticsearchConfig elasticsearchConfig;
    
    /**
     * Index một sản phẩm đơn lẻ
     */
    @CircuitBreaker(name = "elasticsearch", fallbackMethod = "indexProductFallback")
    @Retry(name = "elasticsearch")
    public boolean indexProduct(ProductDocument product) {
        try {
            // Đảm bảo có dữ liệu cho auto-suggest
            if (product.getNameSuggest() == null && product.getName() != null) {
                Completion completion = new Completion(Collections.singletonList(product.getName()));
                product.setNameSuggest(completion);
            }
            
            // Đảm bảo có giá trị inStock dựa vào stockQuantity
            if (product.getInStock() == null && product.getStockQuantity() != null) {
                product.setInStock(product.getStockQuantity() > 0);
            }
            
            String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
            
            IndexRequest<ProductDocument> request = IndexRequest.of(r -> r
                    .index(indexName)
                    .id(product.getId())
                    .document(product));
            
            client.index(request);
            log.info("Product indexed successfully: {}", product.getId());
            return true;
        } catch (Exception e) {
            log.error("Failed to index product: {}", product.getId(), e);
            throw new ElasticsearchException("Failed to index product: " + product.getId(), e);
        }
    }
    
    /**
     * Xóa sản phẩm khỏi index
     */
    @CircuitBreaker(name = "elasticsearch", fallbackMethod = "deleteProductFallback")
    @Retry(name = "elasticsearch")
    public void deleteProduct(String productId) {
        try {
            String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
            
            DeleteRequest request = DeleteRequest.of(r -> r
                    .index(indexName)
                    .id(productId));
            
            client.delete(request);
            log.info("Product deleted from index: {}", productId);
        } catch (Exception e) {
            log.error("Failed to delete product from index: {}", productId, e);
            throw new RuntimeException("Failed to delete product from index", e);
        }
    }
    
    /**
     * Bulk indexing cho nhiều sản phẩm - tối ưu hiệu suất
     */
    @CircuitBreaker(name = "elasticsearch", fallbackMethod = "bulkIndexProductsFallback")
    @Retry(name = "elasticsearch")
    public boolean bulkIndexProducts(List<ProductDocument> products) {
        if (products == null || products.isEmpty()) {
            return true;
        }
        
        try {
            String indexName = elasticsearchConfig.getIndexSettings().getProducts().getName();
            
            BulkRequest.Builder bulkRequestBuilder = new BulkRequest.Builder();
            
            for (ProductDocument product : products) {
                // Đảm bảo có dữ liệu cho auto-suggest
                if (product.getNameSuggest() == null && product.getName() != null) {
                    Completion completion = new Completion(Collections.singletonList(product.getName()));
                    product.setNameSuggest(completion);
                }
                
                // Đảm bảo có giá trị inStock dựa vào stockQuantity
                if (product.getInStock() == null && product.getStockQuantity() != null) {
                    product.setInStock(product.getStockQuantity() > 0);
                }
                
                bulkRequestBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(indexName)
                                .id(product.getId())
                                .document(product)));
            }
            
            BulkResponse response = client.bulk(bulkRequestBuilder.build());
            
            if (response.errors()) {
                log.error("Bulk indexing has errors: {}", 
                        response.items().stream()
                                .filter(item -> item.error() != null)
                                .map(item -> item.id() + ": " + item.error().reason())
                                .collect(Collectors.joining(", ")));
                return false;
            }
            
            log.info("Bulk indexed {} products successfully", products.size());
            return true;
        } catch (Exception e) {
            log.error("Failed to bulk index {} products", products.size(), e);
            throw new RuntimeException("Failed to bulk index products", e);
        }
    }
    
    /**
     * Batch processing để xử lý số lượng lớn sản phẩm
     */
    public List<CompletableFuture<Boolean>> batchIndexProducts(List<ProductDocument> products, int batchSize) {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        
        for (int i = 0; i < products.size(); i += batchSize) {
            int endIdx = Math.min(i + batchSize, products.size());
            List<ProductDocument> batch = products.subList(i, endIdx);
            
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return bulkIndexProducts(batch);
                } catch (Exception e) {
                    log.error("Error in batch indexing", e);
                    return false;
                }
            });
            
            futures.add(future);
        }
        
        return futures;
    }
    
    // Fallback methods
    public boolean indexProductFallback(ProductDocument product, Exception ex) {
        log.error("Fallback for indexProduct: {}", ex.getMessage());
        // Có thể lưu vào queue để xử lý lại sau
        return false;
    }
    
    public boolean deleteProductFallback(String productId, Exception ex) {
        log.error("Fallback for deleteProduct: {}", ex.getMessage());
        return false;
    }
    
    public boolean bulkIndexProductsFallback(List<ProductDocument> products, Exception ex) {
        log.error("Fallback for bulkIndexProducts: {}", ex.getMessage());
        // Có thể thử index từng sản phẩm một
        return false;
    }
} 