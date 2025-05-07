package com.fourj.orderservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Cấu hình múi giờ mặc định cho ứng dụng
 */
@Configuration
@Slf4j
public class TimeZoneConfig {

    private static final String VIETNAM_TIMEZONE = "Asia/Ho_Chi_Minh";

    /**
     * Thiết lập múi giờ mặc định cho JVM khi ứng dụng khởi động
     */
    @PostConstruct
    public void init() {
        // Thiết lập múi giờ mặc định cho JVM là Asia/Ho_Chi_Minh
        TimeZone.setDefault(TimeZone.getTimeZone(VIETNAM_TIMEZONE));
        log.info("Đã thiết lập múi giờ mặc định: {}", TimeZone.getDefault().getID());
    }
} 