package com.fourj.searchservice.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourj.searchservice.document.ProductDocument;
import com.fourj.searchservice.service.ProductIndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventListener {

    private final ObjectMapper objectMapper;
    private final ProductIndexingService productIndexingService;
    
    private static final int BATCH_SIZE = 100;
    private final List<ProductDocument> batchBuffer = Collections.synchronizedList(new ArrayList<>(BATCH_SIZE));
    private final AtomicInteger batchCounter = new AtomicInteger(0);
    private final ReentrantLock batchLock = new ReentrantLock();
    
    @KafkaListener(topics = "${kafka.topics.product-events:product-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void handleProductEvent(String payload) {
        try {
            JsonNode eventNode = objectMapper.readTree(payload);
            String eventType = eventNode.path("eventType").asText();
            
            log.debug("Received product event: type={}", eventType);
            
            switch (eventType) {
                case "PRODUCT_CREATED":
                case "PRODUCT_UPDATED":
                    JsonNode productNode = eventNode.path("payload");
                    
                    // Chuyển đổi từ Product model sang ProductDocument
                    ProductDocument product = convertToProductDocument(productNode);
                    
                    if (product != null) {
                        handleProductCreateOrUpdate(product);
                    } else {
                        log.warn("Could not convert product payload for id: {}", 
                                eventNode.path("productId").asText());
                    }
                    break;
                    
                case "PRODUCT_DELETED":
                    String productId = eventNode.path("productId").asText();
                    productIndexingService.deleteProduct(productId);
                    break;
                    
                case "PRODUCT_BULK_UPDATED":
                    List<ProductDocument> products = new ArrayList<>();
                    JsonNode productsNode = eventNode.path("payload");
                    
                    if (productsNode.isArray()) {
                        for (JsonNode node : productsNode) {
                            ProductDocument doc = convertToProductDocument(node);
                            if (doc != null) {
                                products.add(doc);
                            }
                        }
                        
                        if (!products.isEmpty()) {
                            productIndexingService.bulkIndexProducts(products);
                        }
                    }
                    break;
                    
                default:
                    log.warn("Unknown event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Error processing product event: {}", payload, e);
            // Cân nhắc gửi đến dead-letter-queue
        }
    }
    
    /**
     * Chuyển đổi từ Product model sang ProductDocument
     */
    private ProductDocument convertToProductDocument(JsonNode productNode) {
        try {
            Long id = productNode.path("id").asLong();
            String name = productNode.path("name").asText();
            // Xử lý createdAt
            LocalDateTime createdAt;
            if (productNode.has("createdAt") && !productNode.path("createdAt").asText().isEmpty()) {
                try {
                    createdAt = LocalDateTime.parse(productNode.path("createdAt").asText());
                } catch (DateTimeParseException e) {
                    log.warn("Invalid createdAt format: {}, using current time",
                            productNode.path("createdAt").asText());
                    createdAt = LocalDateTime.now();
                }
            } else {
                createdAt = LocalDateTime.now();
            }

            // Xử lý updatedAt
            LocalDateTime updatedAt;
            if (productNode.has("updatedAt") && !productNode.path("updatedAt").asText().isEmpty()) {
                try {
                    updatedAt = LocalDateTime.parse(productNode.path("updatedAt").asText());
                } catch (DateTimeParseException e) {
                    log.warn("Invalid updatedAt format: {}, using current time",
                            productNode.path("updatedAt").asText());
                    updatedAt = LocalDateTime.now();
                }
            } else {
                updatedAt = LocalDateTime.now();
            }
            
            ProductDocument document = ProductDocument.builder()
                    .id(String.valueOf(id))
                    .name(name)
                    .description(productNode.path("description").asText())
                    .price(productNode.has("price") ? 
                            new BigDecimal(productNode.path("price").asText()) : null)
                    .stockQuantity(productNode.path("stockQuantity").asInt())
                    .imageUrl(productNode.path("imageUrl").asText())
                    .categoryId(productNode.path("categoryId").asLong())
                    .categoryName(productNode.path("categoryName").asText())
                    .active(productNode.path("active").asBoolean(true))
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .inStock(productNode.path("stockQuantity").asInt() > 0)
                    .build();
            
            // Thêm completion cho search suggest
            Completion nameSuggest = new Completion(Collections.singletonList(name));
            document.setNameSuggest(nameSuggest);
            
            // Trích xuất attributes nếu có
            if (productNode.has("attributes") && productNode.path("attributes").isArray()) {
                List<ProductDocument.ProductAttribute> attributes = new ArrayList<>();
                
                for (JsonNode attrNode : productNode.path("attributes")) {
                    ProductDocument.ProductAttribute attribute = ProductDocument.ProductAttribute.builder()
                            .name(attrNode.path("name").asText())
                            .value(attrNode.path("value").asText())
                            .displayName(attrNode.path("name").asText())
                            .displayValue(attrNode.path("value").asText())
                            .build();
                    
                    attributes.add(attribute);
                }
                
                document.setAttributes(attributes);
            }
            
            return document;
        } catch (Exception e) {
            log.error("Error converting product node to document", e);
            return null;
        }
    }
    
    /**
     * Xử lý tạo hoặc cập nhật sản phẩm
     * Tối ưu bằng cách gom nhóm các sản phẩm để bulk index
     */
    private void handleProductCreateOrUpdate(ProductDocument product) {
        try {
            // Đầu tiên, luôn đảm bảo sản phẩm mới được indexed ngay lập tức
            try {
                productIndexingService.indexProduct(product);
                log.info("Product immediately indexed: {}", product.getId());
            } catch (Exception e) {
                log.error("Failed to immediately index product: {}", product.getId(), e);
            }
            
            // Vẫn giữ logic batch cho các cập nhật tiếp theo
            batchLock.lock();
            try {
                // Thêm sản phẩm vào buffer
                batchBuffer.add(product);
                int currentSize = batchCounter.incrementAndGet();
                
                // Nếu buffer đầy, thực hiện bulk index
                if (currentSize >= BATCH_SIZE) {
                    List<ProductDocument> batchToProcess = new ArrayList<>(batchBuffer);
                    batchBuffer.clear();
                    batchCounter.set(0);
                    
                    batchLock.unlock(); // Unlock trước khi gọi service để tránh blocking
                    
                    try {
                        productIndexingService.bulkIndexProducts(batchToProcess);
                    } catch (Exception e) {
                        log.error("Error during bulk indexing, processing individually", e);
                        // Xử lý từng sản phẩm
                        for (ProductDocument doc : batchToProcess) {
                            try {
                                productIndexingService.indexProduct(doc);
                            } catch (Exception ex) {
                                log.error("Failed to index individual product: {}", doc.getId(), ex);
                            }
                        }
                    }
                } else {
                    batchLock.unlock(); // Unlock nếu chưa đủ kích thước batch
                }
            } finally {
                if (batchLock.isHeldByCurrentThread()) {
                    batchLock.unlock();
                }
            }
        } catch (Exception e) {
            log.error("Critical error in batch processing: {}", e.getMessage(), e);
            // Luôn đảm bảo xử lý sản phẩm hiện tại
            try {
                productIndexingService.indexProduct(product);
            } catch (Exception ignored) {
                log.error("Failed to index product as fallback: {}", product.getId(), ignored);
            }
        }
    }

    // Thêm scheduler để xử lý batch còn lại nếu không đủ kích thước
    @Scheduled(fixedDelay = 30000) // 30 giây
    public void processRemainingBatch() {
        try {
            batchLock.lock();
            if (!batchBuffer.isEmpty()) {
                log.info("Processing remaining {} items in batch", batchBuffer.size());
                List<ProductDocument> batchToProcess = new ArrayList<>(batchBuffer);
                batchBuffer.clear();
                batchCounter.set(0);
                
                batchLock.unlock();
                
                productIndexingService.bulkIndexProducts(batchToProcess);
            } else {
                batchLock.unlock();
            }
        } catch (Exception e) {
            log.error("Error processing remaining batch", e);
            if (batchLock.isHeldByCurrentThread()) {
                batchLock.unlock();
            }
        }
    }
} 