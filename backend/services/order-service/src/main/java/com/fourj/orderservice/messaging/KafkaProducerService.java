package com.fourj.orderservice.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private static final String TOPIC = "order_created_topic";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    public void sendOrderCreatedEvent(Object orderData) {
        this.kafkaTemplate.send(TOPIC, orderData);
        System.out.println("Sent order created event: " + orderData + " to topic " + TOPIC);
    }
}

