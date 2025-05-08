package com.fourj.orderservice.service;

import java.io.InputStream;

public interface AzureBlobStorageService {
    /**
     * Upload file lên Azure Blob Storage
     * 
     * @param containerName Tên container trên Azure Blob Storage
     * @param blobName Tên file trên Azure Blob Storage
     * @param inputStream Input stream của file cần upload
     * @param contentType Loại nội dung của file (ví dụ: application/vnd.ms-excel)
     * @return URL của file đã upload
     */
    String uploadFile(String containerName, String blobName, InputStream inputStream, String contentType);
} 