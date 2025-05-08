package com.fourj.orderservice.service;

import com.fourj.orderservice.model.Order;

import java.io.InputStream;
import java.util.List;

public interface ExcelService {
    /**
     * Tạo file Excel từ danh sách đơn hàng
     * 
     * @param orders Danh sách đơn hàng cần xuất ra Excel
     * @return InputStream của file Excel đã tạo
     */
    InputStream generateExcelFile(List<Order> orders);
} 