package com.fourj.orderservice.service.impl;

import com.fourj.orderservice.model.Order;
import com.fourj.orderservice.model.OrderItem;
import com.fourj.orderservice.service.ExcelService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j
public class ExcelServiceImpl implements ExcelService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String[] ORDER_HEADERS = {"Mã đơn hàng", "Khách hàng", "Trạng thái", "Tổng tiền", "Ngày tạo", "Ghi chú"};
    private static final String[] ITEM_HEADERS = {"Sản phẩm", "Số lượng", "Đơn giá", "Thành tiền", "Người bán"};

    @Override
    public InputStream generateExcelFile(List<Order> orders) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Tạo sheet "Danh sách đơn hàng"
            Sheet orderSheet = workbook.createSheet("Danh sách đơn hàng");
            
            // Tạo header row cho danh sách đơn hàng
            Row headerRow = orderSheet.createRow(0);
            createHeaderRow(workbook, headerRow, ORDER_HEADERS);
            
            // Thêm dữ liệu đơn hàng
            int rowNum = 1;
            for (Order order : orders) {
                Row row = orderSheet.createRow(rowNum++);
                row.createCell(0).setCellValue(order.getOrderNumber());
                row.createCell(1).setCellValue(order.getUserId());
                row.createCell(2).setCellValue(order.getStatus().toString());
                row.createCell(3).setCellValue(order.getTotalAmount().doubleValue());
                
                if (order.getCreatedAt() != null) {
                    row.createCell(4).setCellValue(order.getCreatedAt().format(DATE_FORMATTER));
                } else {
                    row.createCell(4).setCellValue("");
                }
                
                row.createCell(5).setCellValue(order.getNotes() != null ? order.getNotes() : "");
            }
            
            // Tự động điều chỉnh độ rộng cột
            for (int i = 0; i < ORDER_HEADERS.length; i++) {
                orderSheet.autoSizeColumn(i);
            }
            
            // Tạo sheet "Chi tiết đơn hàng"
            Sheet itemSheet = workbook.createSheet("Chi tiết đơn hàng");
            
            // Tạo header row cho chi tiết đơn hàng
            Row itemHeaderRow = itemSheet.createRow(0);
            // Thêm "Mã đơn hàng" vào đầu danh sách cột
            String[] extendedHeaders = new String[ITEM_HEADERS.length + 1];
            extendedHeaders[0] = "Mã đơn hàng";
            System.arraycopy(ITEM_HEADERS, 0, extendedHeaders, 1, ITEM_HEADERS.length);
            createHeaderRow(workbook, itemHeaderRow, extendedHeaders);
            
            // Thêm dữ liệu chi tiết đơn hàng
            rowNum = 1;
            for (Order order : orders) {
                for (OrderItem item : order.getItems()) {
                    Row row = itemSheet.createRow(rowNum++);
                    row.createCell(0).setCellValue(order.getOrderNumber());
                    row.createCell(1).setCellValue(item.getProductName());
                    row.createCell(2).setCellValue(item.getQuantity());
                    row.createCell(3).setCellValue(item.getPrice().doubleValue());
                    row.createCell(4).setCellValue(item.getSubtotal().doubleValue());
                    row.createCell(5).setCellValue(item.getSellerId() != null ? item.getSellerId() : "");
                }
            }
            
            // Tự động điều chỉnh độ rộng cột
            for (int i = 0; i < extendedHeaders.length; i++) {
                itemSheet.autoSizeColumn(i);
            }
            
            // Ghi workbook vào ByteArrayOutputStream
            workbook.write(out);
            
            // Chuyển đổi ByteArrayOutputStream thành ByteArrayInputStream
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            log.error("Lỗi khi tạo file Excel", e);
            throw new RuntimeException("Lỗi khi tạo file Excel", e);
        }
    }
    
    private void createHeaderRow(Workbook workbook, Row headerRow, String[] headers) {
        // Tạo cell style cho header
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        
        // Tạo cells
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }
} 