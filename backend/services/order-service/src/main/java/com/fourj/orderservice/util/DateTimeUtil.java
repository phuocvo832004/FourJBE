package com.fourj.orderservice.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lớp tiện ích để xử lý múi giờ trong ứng dụng
 */
@Slf4j
public class DateTimeUtil {

    private static final String DEFAULT_TIMEZONE = "UTC";
    private static final String VIETNAM_TIMEZONE = "Asia/Ho_Chi_Minh";
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter VIETNAM_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /**
     * Chuyển đổi LocalDateTime sang chuỗi ISO 8601 với múi giờ UTC
     */
    public static String toISOString(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        
        // Chuyển LocalDateTime sang ZonedDateTime với múi giờ UTC
        ZonedDateTime zonedDateTime = dateTime.atZone(ZoneId.of(DEFAULT_TIMEZONE));
        return zonedDateTime.format(ISO_FORMATTER);
    }

    /**
     * Chuyển đổi LocalDateTime sang chuỗi với định dạng Việt Nam (múi giờ Asia/Ho_Chi_Minh)
     */
    public static String toVietnamString(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        
        // Chuyển LocalDateTime sang ZonedDateTime với múi giờ Việt Nam
        ZonedDateTime zonedDateTime = dateTime.atZone(ZoneId.of(DEFAULT_TIMEZONE))
                .withZoneSameInstant(ZoneId.of(VIETNAM_TIMEZONE));
        return zonedDateTime.format(VIETNAM_FORMATTER);
    }

    /**
     * Chuyển đổi LocalDateTime sang LocalDateTime trong múi giờ Việt Nam
     */
    public static LocalDateTime toVietnamDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        
        // Chuyển từ LocalDateTime (giả sử là UTC) sang LocalDateTime ở múi giờ Việt Nam
        ZonedDateTime utcZoned = dateTime.atZone(ZoneId.of(DEFAULT_TIMEZONE));
        return utcZoned.withZoneSameInstant(ZoneId.of(VIETNAM_TIMEZONE)).toLocalDateTime();
    }

    /**
     * Lấy thời gian hiện tại trong múi giờ Việt Nam
     */
    public static LocalDateTime nowInVietnam() {
        return ZonedDateTime.now(ZoneId.of(VIETNAM_TIMEZONE)).toLocalDateTime();
    }

    /**
     * Chuyển đổi timestamp từ múi giờ khác sang múi giờ Việt Nam
     */
    public static LocalDateTime fromTimestampToVietnam(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        return LocalDateTime.ofInstant(instant, ZoneId.of(VIETNAM_TIMEZONE));
    }

    /**
     * Chuyển đổi chuỗi ngày từ frontend sang LocalDateTime với múi giờ Việt Nam
     */
    public static LocalDateTime parseFromFrontend(String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        
        try {
            // Nếu có thông tin múi giờ, xử lý theo đó
            if (dateString.contains("Z") || dateString.contains("+")) {
                Instant instant = Instant.parse(dateString);
                return LocalDateTime.ofInstant(instant, ZoneId.of(VIETNAM_TIMEZONE));
            }
            
            // Nếu không có thông tin múi giờ, xử lý như định dạng ISO 8601 cơ bản
            LocalDateTime dateTime = LocalDateTime.parse(dateString, DateTimeFormatter.ISO_DATE_TIME);
            return toVietnamDateTime(dateTime);
        } catch (Exception e) {
            log.error("Lỗi khi phân tích chuỗi ngày: {}", dateString, e);
            return null;
        }
    }
    
    /**
     * Chuyển đổi chuỗi ngày từ frontend (chỉ có ngày, không có giờ) sang LocalDateTime với múi giờ Việt Nam
     */
    public static LocalDateTime parseFromFrontendDate(String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        
        try {
            // Xử lý định dạng chỉ có ngày (yyyy-MM-dd)
            DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;
            LocalDateTime dateTime = LocalDateTime.of(java.time.LocalDate.parse(dateString, formatter), java.time.LocalTime.MIDNIGHT);
            return dateTime.atZone(ZoneId.of(VIETNAM_TIMEZONE)).toLocalDateTime();
        } catch (Exception e) {
            log.error("Lỗi khi phân tích chuỗi ngày: {}", dateString, e);
            return null;
        }
    }
} 