package com.fourj.orderservice.controller;

import com.fourj.orderservice.service.OrderExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/orders/export")
@RequiredArgsConstructor
@Slf4j
public class OrderExportController {

    private final OrderExportService orderExportService;

    /**
     * Endpoint để trigger thủ công việc export đơn hàng lên Azure Blob Storage
     * Yêu cầu quyền ADMIN hoặc MANAGER
     *
     * @return ResponseEntity chứa thông tin kết quả export
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_ADMIN', 'SCOPE_MANAGER')")
    public ResponseEntity<Map<String, Object>> triggerOrderExport() {
        log.info("Nhận yêu cầu export đơn hàng thủ công");
        
        try {
            String fileUrl = orderExportService.exportOrdersToExcel();
            
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
} 