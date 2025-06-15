package com.fourj.cartservice.service;

import com.fourj.cartservice.dto.ProductDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ProductClient {

    private final WebClient webClient;

    @Autowired
    public ProductClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://product-service:8084").build();
    }

    public Mono<ProductDto> getProductById(Long productId) {
        return webClient.get()
                .uri("/api/products/{id}", productId)
                .retrieve()
                .bodyToMono(ProductDto.class);
    }
}