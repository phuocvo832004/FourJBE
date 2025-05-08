package com.fourj.orderservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Cấu hình kích hoạt retry trong ứng dụng
 */
@Configuration
@EnableRetry
public class RetryConfig {
} 