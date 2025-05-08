package com.fourj.orderservice.service.impl;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.fourj.orderservice.service.AzureBlobStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AzureBlobStorageServiceImpl implements AzureBlobStorageService {

    private final BlobServiceClient blobServiceClient;

    @Override
    public String uploadFile(String containerName, String blobName, InputStream inputStream, String contentType) {
        log.info("Bắt đầu upload file {} lên container {}", blobName, containerName);
        
        // Lấy hoặc tạo container nếu chưa tồn tại
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient = blobServiceClient.createBlobContainer(containerName);
            log.info("Đã tạo container mới: {}", containerName);
        }
        
        // Tạo blob client
        BlobClient blobClient = containerClient.getBlobClient(blobName);
        
        // Thiết lập headers
        BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(contentType);
        
        // Upload file
        blobClient.upload(inputStream, true);
        blobClient.setHttpHeaders(headers);
        
        log.info("Upload thành công file {} lên container {}", blobName, containerName);
        
        // Trả về URL của blob
        return blobClient.getBlobUrl();
    }
} 