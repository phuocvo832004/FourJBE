package com.fourj.orderservice.service.client;

import com.fourj.orderservice.dto.CartDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class CartClient {

    private final WebClient webClient;

    @Autowired
    public CartClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://cart-service:8085").build();
    }

    public Mono<CartDto> getCart(String token) {
        return webClient.get()
                .uri("/api/carts")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(CartDto.class);
    }

    public Mono<Void> clearCart(String token) {
        return webClient.delete()
                .uri("/api/carts")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(Void.class);
    }
    
    public Mono<Void> restoreCart(String token, CartDto cartBackup) {
        return webClient.post()
                .uri("/api/carts/restore")
                .header("Authorization", "Bearer " + token)
                .bodyValue(cartBackup)
                .retrieve()
                .bodyToMono(Void.class);
    }
} 