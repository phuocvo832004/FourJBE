package com.fourj.orderservice.controller;

import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.repository.OrderRepository;
import com.fourj.orderservice.service.AzureBlobStorageService;
import com.fourj.orderservice.service.OrderExportService;
import com.fourj.orderservice.service.impl.OrderExportServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/orders/export")
@RequiredArgsConstructor
@Slf4j
public class OrderExportController {

    private final OrderExportService orderExportService;
    private final OrderRepository orderRepository;
    private final AzureBlobStorageService azureBlobStorageService;
    
    @Value("${azure.storage.container-name:orders}")
    private String azureContainerName;

    /**
     * Endpoint để trigger thủ công việc export đơn hàng lên Azure Blob Storage
     * Yêu cầu quyền ADMIN hoặc MANAGER
     *
     * @return ResponseEntity chứa thông tin kết quả export
     */
    @PostMapping
    @PreAuthorize("permitAll")
    public ResponseEntity<Map<String, Object>> triggerOrderExport() {
        log.info("Nhận yêu cầu export đơn hàng thủ công");
        
        try {
            String fileUrl = orderExportService.exportOrdersToCsv();
            
            Map<String, Object> response = new HashMap<>();
            if (fileUrl != null) {
                response.put("success", true);
                response.put("message", "Export đơn hàng thành công");
                response.put("fileUrl", fileUrl);
            } else {
                response.put("success", true);
                response.put("message", "Không có đơn hàng mới để export");
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Lỗi khi export đơn hàng: {}", e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Không thể export đơn hàng: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * Endpoint để test quá trình batch không cần xác thực
     * Chỉ sử dụng cho môi trường phát triển
     *
     * @return ResponseEntity chứa thông tin kết quả test
     */
    @PostMapping("/test-batch")
    @PreAuthorize("permitAll")
    public ResponseEntity<Map<String, Object>> testBatchProcess() {
        log.info("Nhận yêu cầu test batch process");
        
        try {
            String fileUrl = orderExportService.exportOrdersToCsv();
            
            Map<String, Object> response = new HashMap<>();
            if (fileUrl != null) {
                response.put("success", true);
                response.put("message", "Test batch process thành công");
                response.put("fileUrl", fileUrl);
            } else {
                response.put("success", true);
                response.put("message", "Test batch process hoàn tất. Không có đơn hàng mới để export");
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Lỗi khi test batch process: {}", e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Test batch process thất bại: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * Endpoint để test upload một đơn hàng đơn lẻ lên Azure
     * Chỉ sử dụng cho môi trường phát triển
     * 
     * @param orderNumber Số đơn hàng cần upload
     * @return ResponseEntity chứa thông tin kết quả upload
     */
    @PostMapping("/test-single/{orderNumber}")
    @PreAuthorize("permitAll")
    @Transactional
    public ResponseEntity<Map<String, Object>> testSingleOrderExport(@PathVariable String orderNumber) {
        log.info("Nhận yêu cầu test upload đơn hàng đơn lẻ: {}", orderNumber);
        
        try {
            // Tìm đơn hàng theo orderNumber
            Order order = orderRepository.findByOrderNumber(orderNumber)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng: " + orderNumber));
            
            // Export và upload đơn hàng lên Azure
            String fileUrl = orderExportService.exportSingleOrder(order);
            
            Map<String, Object> response = new HashMap<>();
            if (fileUrl != null) {
                response.put("success", true);
                response.put("message", "Upload đơn hàng thành công");
                response.put("fileUrl", fileUrl);
                response.put("orderNumber", orderNumber);
            } else {
                response.put("success", false);
                response.put("message", "Không thể upload đơn hàng. Vui lòng xem log để biết chi tiết.");
                response.put("orderNumber", orderNumber);
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Lỗi khi upload đơn hàng {}: {}", orderNumber, e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Không thể upload đơn hàng: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Endpoint để test kết nối tới Azure Blob Storage
     * Chỉ sử dụng cho môi trường phát triển
     * 
     * @return ResponseEntity chứa thông tin kết quả test
     */
    @PostMapping("/test-azure-connection")
    @PreAuthorize("permitAll")
    public ResponseEntity<Map<String, Object>> testAzureConnection() {
        log.info("Nhận yêu cầu test kết nối tới Azure Blob Storage");
        
        try {
            // Tạo một file text đơn giản để test
            String testContent = "Test Azure Blob Storage connection at " + LocalDateTime.now().toString();
            byte[] testBytes = testContent.getBytes(StandardCharsets.UTF_8);
            
            String fileName = "connection-test-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")) + ".txt";
            String blobName = "test/" + fileName;
            
            try (InputStream dataStream = new ByteArrayInputStream(testBytes)) {
                String fileUrl = azureBlobStorageService.uploadCompleteFile(azureContainerName, blobName, dataStream, "text/plain");
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Kết nối tới Azure Blob Storage thành công");
                response.put("fileUrl", fileUrl);
                
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Lỗi khi test kết nối tới Azure Blob Storage: {}", e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Không thể kết nối tới Azure Blob Storage: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Endpoint để kích hoạt scheduled weekly export task
     * Public API, không yêu cầu xác thực để có thể gọi từ bên ngoài
     * 
     * @return ResponseEntity chứa thông tin kết quả
     */
    @PostMapping("/trigger-weekly-export")
    @PreAuthorize("permitAll")
    @Transactional
    public ResponseEntity<Map<String, Object>> triggerWeeklyExport() {
        log.info("Nhận yêu cầu kích hoạt scheduled weekly export task");
        
        try {
            // Gọi phương thức scheduled thực sự
            ((OrderExportServiceImpl) orderExportService).scheduledWeeklyOrderExport();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Scheduled weekly export task được kích hoạt thành công");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Lỗi khi kích hoạt scheduled weekly export task: {}", e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Không thể kích hoạt scheduled task: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Endpoint để đồng bộ tất cả đơn hàng chưa upload lên Azure ngay lập tức
     * Public API, không yêu cầu xác thực để có thể gọi từ bên ngoài
     * 
     * @return ResponseEntity chứa thông tin kết quả
     */
    @PostMapping("/sync-all-pending")
    @PreAuthorize("permitAll")
    @Transactional
    public ResponseEntity<Map<String, Object>> syncAllPendingOrders() {
        log.info("Nhận yêu cầu đồng bộ tất cả đơn hàng chưa upload lên Azure");
        
        try {
            String fileUrl = ((OrderExportServiceImpl) orderExportService).exportWeeklyOrdersToNewCsvBlob();
            
            Map<String, Object> response = new HashMap<>();
            if (fileUrl != null && !fileUrl.startsWith("No")) {
                response.put("success", true);
                response.put("message", "Đồng bộ tất cả đơn hàng chưa upload thành công");
                response.put("fileUrl", fileUrl);
            } else {
                response.put("success", true);
                response.put("message", "Không có đơn hàng mới nào cần đồng bộ");
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Lỗi khi đồng bộ đơn hàng: {}", e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Không thể đồng bộ đơn hàng: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
} 