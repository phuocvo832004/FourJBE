package com.fourj.cartservice.controller;

import com.fourj.cartservice.dto.AddItemRequest;
import com.fourj.cartservice.dto.CartDto;
import com.fourj.cartservice.dto.CheckoutEventDto;
import com.fourj.cartservice.dto.CheckoutRequestDto;
import com.fourj.cartservice.dto.UpdateItemRequest;
import com.fourj.cartservice.service.CartEventService;
import com.fourj.cartservice.service.CartService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/carts")
public class CartController {

    private final CartService cartService;
    private final CartEventService cartEventService;

    @Autowired
    public CartController(CartService cartService, CartEventService cartEventService) {
        this.cartService = cartService;
        this.cartEventService = cartEventService;
    }

    @GetMapping
    public ResponseEntity<CartDto> getCart(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(cartService.getCartByUserId(userId));
    }

    @PostMapping("/items")
    public ResponseEntity<CartDto> addItemToCart(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddItemRequest request) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(cartService.addItemToCart(userId, request));
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<CartDto> updateCartItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateItemRequest request) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(cartService.updateCartItem(userId, itemId, request));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<CartDto> removeItemFromCart(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long itemId) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(cartService.removeItemFromCart(userId, itemId));
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checkout")
    public ResponseEntity<Void> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CheckoutRequestDto request) {
        String userId = jwt.getSubject();
        CartDto cart = cartService.getCartByUserId(userId);

        // Tạo checkout event
        CheckoutEventDto event = new CheckoutEventDto();
        event.setUserId(userId);
        event.setItems(cart.getItems());
        event.setTotalAmount(cart.getTotalPrice().doubleValue());
        event.setShippingAddress(request.getShippingAddress());
        event.setPaymentMethod(request.getPaymentMethod());

        // Publish event
        cartEventService.publishCheckoutEvent(event);

        // Xóa giỏ hàng sau khi checkout
        cartService.clearCart(userId);

        return ResponseEntity.accepted().build();
    }

    @PostMapping("/restore")
    public ResponseEntity<Void> restoreCart(@AuthenticationPrincipal Jwt jwt,
                                            @RequestBody CartDto cartDto) {
        String userId = jwt.getSubject();
        cartService.restoreCart(userId, cartDto);
        return ResponseEntity.ok().build();
    }
}