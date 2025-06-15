package com.fourj.productservice.config;

import com.fourj.productservice.event.dto.ProductEventDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Cấu hình Kafka cho product-service
 */
@Configuration
public class KafkaConfig {
    
    /**
     * KafkaTemplate để phát sự kiện
     * Bean này được tạo tự động bởi Spring Boot từ cấu hình application.yml
     * nhưng chúng ta thêm ở đây để tường minh hơn
     */
    @Bean
    public KafkaTemplate<String, ProductEventDto> kafkaTemplate(ProducerFactory<String, ProductEventDto> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
} 