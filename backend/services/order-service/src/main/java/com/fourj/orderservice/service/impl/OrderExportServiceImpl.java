package com.fourj.orderservice.service.impl;

import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.model.OrderItem;
import com.fourj.orderservice.repository.OrderRepository;
import com.fourj.orderservice.service.AzureBlobStorageService;
import com.fourj.orderservice.service.OrderExportService;
import com.azure.storage.blob.BlobServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExportServiceImpl implements OrderExportService {

    private final OrderRepository orderRepository;
    private final AzureBlobStorageService azureBlobStorageService;
    private final BlobServiceClient blobServiceClient;

    private static final Lock scheduleLock = new ReentrantLock();
    private static final String CSV_DELIMITER = ",";
    private static final String CSV_NEW_LINE = "\n";
    private static final String NEW_CSV_HEADER = "user_id,product_id,quantity,timestamp";

    @Value("${azure.storage.container-name:orders}")
    private String azureContainerName;

    @Value("${azure.storage.weekly-export.path:processed-interactions/new}")
    private String weeklyExportPath;

    @Override
    public List<Order> getOrdersForExport() {
        log.info("Lấy danh sách toàn bộ đơn hàng chưa upload lên Azure.");
        return orderRepository.findByIsUploadedToAzureFalse(); 
    }

    @Override
    @Transactional
    public void markOrdersAsUploaded(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        log.info("Đánh dấu {} đơn hàng đã được upload lên Azure.", orders.size());
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        try {
            orderRepository.markOrdersAsUploaded(orderIds);
            log.info("Đã cập nhật trạng thái uploadedToAzure=true cho {} đơn hàng.", orders.size());
        } catch (DataAccessException e) {
            log.error("Lỗi khi cập nhật trạng thái đơn hàng đã upload: {}", e.getMessage(), e);
            throw e; 
        }
    }

    @Transactional
    public String exportWeeklyOrdersToNewCsvBlob() {
        log.info("Bắt đầu quá trình export đơn hàng hàng tuần ra file CSV mới trên Azure.");
        List<Order> ordersToExport = getOrdersForExport();

        if (ordersToExport.isEmpty()) {
            log.info("Không có đơn hàng mới nào cần export.");
            return "No new orders to export.";
        }

        LocalDateTime now = LocalDateTime.now();
        String timestampSuffix = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = "orders_" + timestampSuffix + ".csv";
        
        String blobName = weeklyExportPath.endsWith("/") ? weeklyExportPath + fileName : weeklyExportPath + "/" + fileName;

        StringBuilder csvDataBuilder = new StringBuilder();
        csvDataBuilder.append(NEW_CSV_HEADER).append(CSV_NEW_LINE);

        log.info("Chuyển đổi {} đơn hàng (và các item của chúng) sang định dạng CSV.", ordersToExport.size());
        List<Order> successfullyProcessedOrders = new ArrayList<>();

        for (Order order : ordersToExport) {
            if (order.getItems() == null || order.getItems().isEmpty()) {
                log.warn("Đơn hàng ID {} không có items, bỏ qua.", order.getId());
                continue;
            }
            for (OrderItem item : order.getItems()) {
                csvDataBuilder.append(order.getUserId() != null ? order.getUserId() : "").append(CSV_DELIMITER);
                csvDataBuilder.append(item.getProductId() != null ? item.getProductId() : "").append(CSV_DELIMITER);
                csvDataBuilder.append(item.getQuantity() != null ? item.getQuantity().toString() : "0").append(CSV_DELIMITER);
                csvDataBuilder.append(order.getCreatedAt() != null ? order.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "");
                csvDataBuilder.append(CSV_NEW_LINE);
            }
            successfullyProcessedOrders.add(order);
        }
        
        if (successfullyProcessedOrders.isEmpty()) {
            log.info("Không có đơn hàng nào hợp lệ để tạo dữ liệu CSV.");
            return "No valid orders to generate CSV data.";
        }

        String csvContent = csvDataBuilder.toString();
        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);

        try (InputStream dataStream = new ByteArrayInputStream(csvBytes)) {
            log.info("Bắt đầu upload file CSV {} ({} bytes) lên Azure container: {}, blob: {}", fileName, csvBytes.length, azureContainerName, blobName);
            String fileUrl = azureBlobStorageService.uploadCompleteFile(azureContainerName, blobName, dataStream, "text/csv");
            log.info("Upload thành công file CSV {} lên Azure. URL: {}", fileName, fileUrl);

            markOrdersAsUploaded(successfullyProcessedOrders);
            
            return fileUrl;
        } catch (IOException e) {
            log.error("Lỗi nghiêm trọng trong quá trình upload file CSV {} lên Azure: {}", fileName, e.getMessage(), e);
            return null; 
        } catch (Exception e) {
            log.error("Lỗi không xác định trong quá trình export file CSV {}: {}", fileName, e.getMessage(), e);
            return null;
        }
    }
    
    @Scheduled(cron = "${order.export.weekly-cron:0 0 1 ? * SUN}")
    public void scheduledWeeklyOrderExport() {
        boolean locked = false;
        try {
            log.info("Đang thử lấy lock cho tác vụ export CSV đơn hàng hàng tuần...");
            locked = scheduleLock.tryLock(30, TimeUnit.SECONDS);
            
            if (locked) {
                log.info("Bắt đầu tác vụ định kỳ: Export đơn hàng mới ra file CSV mới trên Azure.");
                String result = exportWeeklyOrdersToNewCsvBlob();
                if (result != null && !result.startsWith("No")) { 
                    log.info("Hoàn thành tác vụ export CSV hàng tuần. File được tạo/cập nhật tại Azure. Result: {}", result);
                } else {
                    log.info("Hoàn thành tác vụ export CSV hàng tuần. Result: {}", result);
                }
            } else {
                log.info("Không thể lấy lock, tác vụ export CSV đơn hàng hàng tuần có thể được thực hiện bởi instance khác.");
            }
        } catch (InterruptedException e) {
            log.error("Tác vụ lấy lock bị gián đoạn: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Lỗi trong tác vụ định kỳ export CSV đơn hàng hàng tuần: {}", e.getMessage(), e);
        } finally {
            if (locked) {
                scheduleLock.unlock();
                log.info("Đã giải phóng lock cho tác vụ export CSV đơn hàng hàng tuần.");
            }
        }
    }
    
    @Override
    @Transactional
    public String exportOrdersToCsv() {
        log.info("Trigger thủ công: Xuất tất cả đơn hàng mới ra file CSV và upload lên Azure Blob Storage");
        return exportWeeklyOrdersToNewCsvBlob();
    }

    @Override
    public String exportSingleOrder(Order order) {
        log.info("Bắt đầu export đơn hàng đơn lẻ ID: {} ra file CSV và upload lên Azure", order.getId());
        
        if (order == null || order.getItems() == null || order.getItems().isEmpty()) {
            log.warn("Đơn hàng không hợp lệ hoặc không có sản phẩm nào.");
            return null;
        }
        
        LocalDateTime now = LocalDateTime.now();
        String timestampSuffix = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = "order_" + order.getOrderNumber() + "_" + timestampSuffix + ".csv";
        
        String blobName = "processed-interactions/new/" + fileName;
        
        StringBuilder csvDataBuilder = new StringBuilder();
        csvDataBuilder.append(NEW_CSV_HEADER).append(CSV_NEW_LINE);
        
        for (OrderItem item : order.getItems()) {
            csvDataBuilder.append(order.getUserId() != null ? order.getUserId() : "").append(CSV_DELIMITER);
            csvDataBuilder.append(item.getProductId() != null ? item.getProductId() : "").append(CSV_DELIMITER);
            csvDataBuilder.append(item.getQuantity() != null ? item.getQuantity().toString() : "0").append(CSV_DELIMITER);
            csvDataBuilder.append(order.getCreatedAt() != null ? order.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "");
            csvDataBuilder.append(CSV_NEW_LINE);
        }
        
        String csvContent = csvDataBuilder.toString();
        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        
        try (InputStream dataStream = new ByteArrayInputStream(csvBytes)) {
            log.info("Upload file CSV {} ({} bytes) lên Azure container: {}, blob: {}", 
                    fileName, csvBytes.length, azureContainerName, blobName);
            String fileUrl = azureBlobStorageService.uploadCompleteFile(azureContainerName, blobName, dataStream, "text/csv");
            log.info("Upload thành công đơn hàng đơn lẻ lên Azure. URL: {}", fileUrl);
            
            // Đánh dấu đơn hàng đã được upload
            List<Order> orders = new ArrayList<>();
            orders.add(order);
            markOrdersAsUploaded(orders);
            
            return fileUrl;
        } catch (IOException e) {
            log.error("Lỗi khi upload đơn hàng đơn lẻ lên Azure: {}", e.getMessage(), e);
            return null;
        }
    }
} 