package com.fourj.cartservice.service;

import com.fourj.cartservice.config.RabbitMQConfig;
import com.fourj.cartservice.dto.CheckoutEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartEventService {

    private final RabbitTemplate rabbitTemplate;

    public void publishCheckoutEvent(CheckoutEventDto checkoutEvent) {
        log.info("Publishing checkout event for user ID: {}", checkoutEvent.getUserId());
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_CHECKOUT,
                RabbitMQConfig.ROUTING_KEY_CHECKOUT,
                checkoutEvent
        );
        log.info("Checkout event published successfully");
    }
}