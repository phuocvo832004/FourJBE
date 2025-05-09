package com.fourj.orderservice.service;

import java.io.InputStream;
import java.io.IOException;

public interface AzureBlobStorageService {
    /**
     * Upload một file hoàn chỉnh lên Azure Blob Storage (thường là BlockBlob).
     * Phương thức này có thể không phù hợp cho orders.xls nếu nó là AppendBlob.
     * 
     * @param containerName Tên container trên Azure Blob Storage
     * @param blobName Tên file trên Azure Blob Storage
     * @param inputStream Input stream của file cần upload
     * @param contentType Loại nội dung của file
     * @return URL của file đã upload
     */
    String uploadCompleteFile(String containerName, String blobName, InputStream inputStream, String contentType) throws IOException;

    /**
     * Download file từ Azure Blob Storage.
     *
     * @param containerName Tên container trên Azure Blob Storage
     * @param blobName Tên file trên Azure Blob Storage
     * @return InputStream của file đã download, hoặc null nếu file không tồn tại.
     * @throws IOException Nếu có lỗi xảy ra khi download.
     */
    InputStream downloadFile(String containerName, String blobName) throws IOException;

    /**
     * Kiểm tra sự tồn tại của một blob.
     * @param containerName Tên container
     * @param blobName Tên blob
     * @return true nếu blob tồn tại, false nếu không.
     * @throws IOException Nếu có lỗi khi tương tác với Azure.
     */
    boolean blobExists(String containerName, String blobName) throws IOException;
} 