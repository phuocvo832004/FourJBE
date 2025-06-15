package com.fourj.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Cấu hình Jackson để xử lý múi giờ khi serialize/deserialize JSON
 */
@Configuration
public class JacksonConfig {

    public static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_FORMAT);

    @Primary
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        
        // Đặt múi giờ mặc định cho ObjectMapper là UTC
        objectMapper.setTimeZone(TimeZone.getTimeZone("UTC"));
        
        // Đăng ký module thời gian Java 8
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        
        // Tuỳ chỉnh serializer cho LocalDateTime để luôn lưu ở định dạng ISO với múi giờ
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATETIME_FORMATTER));
        
        objectMapper.registerModule(javaTimeModule);
        
        // Tắt chức năng viết ngày dưới dạng timestamp
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        
        return objectMapper;
    }
} 