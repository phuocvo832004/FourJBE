package com.fourj.orderservice.service.impl;

import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.repository.OrderRepository;
import com.fourj.orderservice.service.AzureBlobStorageService;
import com.fourj.orderservice.service.ExcelService;
import com.fourj.orderservice.service.OrderExportService;
import com.fourj.orderservice.util.DateTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExportServiceImpl implements OrderExportService {

    private final OrderRepository orderRepository;
    private final ExcelService excelService;
    private final AzureBlobStorageService azureBlobStorageService;

    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Lock scheduleLock = new ReentrantLock();

    @Value("${azure.storage.container-name:orders}")
    private String containerName;
    
    @Value("${order.export.batch-size:1000}")
    private int batchSize;
    
    @Value("${order.export.max-retry:3}")
    private int maxRetry;
    
    @Value("${order.export.history-days:90}")
    private int historyDays;

    @Override
    public List<Order> getOrdersForExport() {
        log.info("Lấy danh sách đơn hàng chưa upload (tối đa {} đơn hàng)", batchSize);
        
        // Lấy đơn hàng theo batch với số lượng giới hạn
        Pageable pageable = PageRequest.of(0, batchSize);
        
        // Nếu cần giới hạn theo thời gian (chỉ lấy đơn hàng trong N ngày gần đây)
        if (historyDays > 0) {
            LocalDateTime startDate = LocalDate.now().minusDays(historyDays).atStartOfDay();
            LocalDateTime endDate = LocalDateTime.now();
            return orderRepository.findOrdersForExportInDateRange(startDate, endDate, pageable);
        } else {
            return orderRepository.findOrdersForExport(pageable);
        }
    }

    @Override
    @Transactional
    public void markOrdersAsUploaded(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }

        log.info("Đánh dấu {} đơn hàng đã được upload", orders.size());
        
        try {
            for (Order order : orders) {
                order.setUploadedToAzure(true);
            }
            orderRepository.saveAll(orders);
            log.info("Đã cập nhật trạng thái cho {} đơn hàng", orders.size());
        } catch (DataAccessException e) {
            log.error("Lỗi khi cập nhật trạng thái đơn hàng: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    @Transactional
    @Retryable(value = {IOException.class, RuntimeException.class}, 
               maxAttempts = 3, 
               backoff = @Backoff(delay = 1000, multiplier = 2))
    public String exportOrdersToExcel() {
        List<Order> orders = new ArrayList<>();
        String fileUrl = null;
        
        try {
            // Lấy danh sách đơn hàng chưa upload
            orders = getOrdersForExport();
            
            if (orders.isEmpty()) {
                log.info("Không có đơn hàng mới để export");
                return null;
            }
            
            log.info("Bắt đầu export {} đơn hàng", orders.size());
            
            // Tạo file Excel
            InputStream excelInputStream = excelService.generateExcelFile(orders);
            
            // Tạo tên file với định dạng orders_yyyyMMdd_HHmmss.xlsx
            String fileName = "orders_" + LocalDateTime.now().format(FILE_DATE_FORMATTER) + ".xlsx";
            
            // Upload file lên Azure Blob Storage
            fileUrl = azureBlobStorageService.uploadFile(
                    containerName,
                    fileName,
                    excelInputStream,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            );
            
            log.info("Export và upload thành công: {}", fileUrl);
            
            // Đánh dấu các đơn hàng đã được upload
            markOrdersAsUploaded(orders);
            
            return fileUrl;
        } catch (Exception e) {
            log.error("Lỗi khi export đơn hàng: {}", e.getMessage(), e);
            // Nếu upload file thành công nhưng việc đánh dấu thất bại
            if (fileUrl != null) {
                log.warn("File đã được upload nhưng không cập nhật được trạng thái đơn hàng: {}", fileUrl);
            }
            throw new RuntimeException("Không thể export đơn hàng", e);
        }
    }

    /**
     * Lên lịch export đơn hàng mỗi 3 ngày vào lúc 1 giờ sáng
     * cron format: giây phút giờ ngày tháng thứ
     */
    @Scheduled(cron = "0 0 1 */3 * *")
    public void scheduledExportOrders() {
        // Sử dụng lock để đảm bảo chỉ một instance của ứng dụng thực hiện tác vụ này
        boolean locked = false;
        try {
            log.info("Đang thử lấy lock cho tác vụ export đơn hàng...");
            locked = scheduleLock.tryLock(30, TimeUnit.SECONDS);
            
            if (locked) {
                log.info("Bắt đầu tác vụ định kỳ export đơn hàng");
                
                String fileUrl = exportOrdersToExcel();
                if (fileUrl != null) {
                    log.info("Hoàn thành tác vụ định kỳ, file đã được upload tại: {}", fileUrl);
                } else {
                    log.info("Hoàn thành tác vụ định kỳ, không có file nào được upload");
                }
                
                // Tiếp tục xử lý các batch tiếp theo nếu còn đơn hàng chưa upload
                processPendingBatches();
            } else {
                log.info("Không thể lấy lock, tác vụ export đơn hàng đã được thực hiện bởi instance khác");
            }
        } catch (Exception e) {
            log.error("Lỗi trong tác vụ định kỳ export đơn hàng", e);
        } finally {
            if (locked) {
                scheduleLock.unlock();
                log.info("Đã giải phóng lock cho tác vụ export đơn hàng");
            }
        }
    }
    
    /**
     * Xử lý các batch tiếp theo nếu còn đơn hàng chưa upload
     */
    private void processPendingBatches() {
        boolean hasMoreOrders = true;
        int batchCount = 1;
        
        while (hasMoreOrders) {
            List<Order> pendingOrders = getOrdersForExport();
            if (pendingOrders.isEmpty()) {
                hasMoreOrders = false;
                log.info("Đã xử lý tất cả các đơn hàng chưa upload");
            } else {
                log.info("Xử lý batch #{} với {} đơn hàng", ++batchCount, pendingOrders.size());
                try {
                    String fileUrl = exportOrdersToExcel();
                    log.info("Đã upload batch #{}, file: {}", batchCount, fileUrl);
                } catch (Exception e) {
                    log.error("Lỗi khi xử lý batch #{}: {}", batchCount, e.getMessage());
                    break; // Dừng nếu gặp lỗi
                }
            }
        }
    }

    /**
     * Export một đơn hàng mới lên Azure Blob Storage
     * Phương thức này tạo file Excel riêng cho một đơn hàng
     */
    @Override
    @Transactional
    @Retryable(value = {IOException.class, RuntimeException.class}, 
               maxAttempts = 3, 
               backoff = @Backoff(delay = 1000, multiplier = 2))
    public String exportSingleOrder(Order order) {
        if (order == null) {
            log.warn("Không thể export đơn hàng null");
            return null;
        }
        
        String fileUrl = null;
        List<Order> singleOrderList = new ArrayList<>();
        singleOrderList.add(order);
        
        try {
            log.info("Bắt đầu export đơn hàng mới #{}", order.getOrderNumber());
            
            // Tạo file Excel
            InputStream excelInputStream = excelService.generateExcelFile(singleOrderList);
            
            // Tạo tên file với định dạng order_[orderNumber]_yyyyMMdd_HHmmss.xlsx
            String fileName = "order_" + order.getOrderNumber() + "_" + 
                    LocalDateTime.now().format(FILE_DATE_FORMATTER) + ".xlsx";
            
            // Upload file lên Azure Blob Storage
            fileUrl = azureBlobStorageService.uploadFile(
                    containerName,
                    fileName,
                    excelInputStream,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            );
            
            // Đánh dấu đơn hàng đã được upload
            order.setUploadedToAzure(true);
            orderRepository.save(order);
            
            log.info("Đã export và upload đơn hàng #{} thành công: {}", order.getOrderNumber(), fileUrl);
            
            return fileUrl;
        } catch (Exception e) {
            log.error("Lỗi khi export đơn hàng #{}: {}", order.getOrderNumber(), e.getMessage(), e);
            throw new RuntimeException("Không thể export đơn hàng " + order.getOrderNumber(), e);
        }
    }
} 