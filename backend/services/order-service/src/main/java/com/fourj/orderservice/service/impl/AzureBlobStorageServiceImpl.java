package com.fourj.orderservice.service.impl;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.specialized.AppendBlobClient;
import com.fourj.orderservice.service.AzureBlobStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AzureBlobStorageServiceImpl implements AzureBlobStorageService {

    private final BlobServiceClient blobServiceClient;

    @Override
    public String uploadCompleteFile(String containerName, String blobName, InputStream inputStream, String contentType) throws IOException {
        log.info("Bắt đầu upload file {} (dạng BlockBlob) lên container {}", blobName, containerName);
        try {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                containerClient.create();
                log.info("Đã tạo container mới: {}", containerName);
            }
            
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(contentType);
            blobClient.upload(inputStream, true);
            blobClient.setHttpHeaders(headers);
            
            log.info("Upload thành công file {} lên container {}. URL: {}", blobName, containerName, blobClient.getBlobUrl());
            return blobClient.getBlobUrl();
        } catch (Exception e) {
            log.error("Lỗi khi upload file {} lên container {}: {}", blobName, containerName, e.getMessage(), e);
            throw new IOException("Lỗi khi upload file: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String containerName, String blobName) throws IOException {
        log.info("Bắt đầu download file {} từ container {}", blobName, containerName);
        try {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                log.warn("Container {} không tồn tại.", containerName);
                return null;
            }

            BlobClient blobClient = containerClient.getBlobClient(blobName);
            if (!blobClient.exists()) {
                log.warn("File {} không tồn tại trong container {}.", blobName, containerName);
                return null;
            }

            InputStream inputStream = blobClient.openInputStream();
            log.info("Download thành công file {} từ container {}.", blobName, containerName);
            return inputStream;
        } catch (Exception e) {
            log.error("Lỗi khi download file {} từ container {}: {}", blobName, containerName, e.getMessage(), e);
            throw new IOException("Lỗi khi download file: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean blobExists(String containerName, String blobName) throws IOException {
        try {
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                return false;
            }
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            return blobClient.exists();
        } catch (Exception e) {
            log.error("Lỗi khi kiểm tra sự tồn tại của blob {}/ {}: {}", containerName, blobName, e.getMessage(), e);
            throw new IOException("Lỗi khi kiểm tra sự tồn tại của blob: " + e.getMessage(), e);
        }
    }

} 