package com.fourj.cartservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    
    public static final String QUEUE_CHECKOUT = "checkout-queue";
    public static final String EXCHANGE_CHECKOUT = "checkout-exchange";
    public static final String ROUTING_KEY_CHECKOUT = "checkout.event";
    
    @Bean
    public Queue checkoutQueue() {
        return new Queue(QUEUE_CHECKOUT, true);
    }
    
    @Bean
    public DirectExchange checkoutExchange() {
        return new DirectExchange(EXCHANGE_CHECKOUT);
    }
    
    @Bean
    public Binding checkoutBinding(Queue checkoutQueue, DirectExchange checkoutExchange) {
        return BindingBuilder.bind(checkoutQueue)
                .to(checkoutExchange)
                .with(ROUTING_KEY_CHECKOUT);
    }
    
    @Bean
    public Jackson2JsonMessageConverter converter() {
        return new Jackson2JsonMessageConverter();
    }
    
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(converter());
        return rabbitTemplate;
    }
}
