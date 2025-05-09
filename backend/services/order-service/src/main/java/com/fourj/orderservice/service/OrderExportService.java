package com.fourj.orderservice.service;

import com.fourj.orderservice.model.Order;

import java.util.List;

public interface OrderExportService {
    /**
     * Lấy danh sách đơn hàng chưa được upload lên Azure
     *
     * @return Danh sách đơn hàng chưa được upload
     */
    List<Order> getOrdersForExport();

    /**
     * Đánh dấu đơn hàng đã được upload lên Azure
     *
     * @param orders Danh sách đơn hàng đã được upload
     */
    void markOrdersAsUploaded(List<Order> orders);

    /**
     * Export đơn hàng ra file Excel và upload lên Azure Blob Storage
     * Phương thức này sẽ được lên lịch chạy định kỳ
     *
     * @return URL của file Excel đã upload
     */
    String exportOrdersToCsv();

    /**
     * Export một đơn hàng mới ra file Excel và upload lên Azure Blob Storage
     * Phương thức này được gọi khi tạo đơn hàng mới
     *
     * @param order Đơn hàng cần export
     * @return URL của file Excel đã upload
     */
    String exportSingleOrder(Order order);
} 