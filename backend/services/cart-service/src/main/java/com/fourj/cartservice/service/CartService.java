package com.fourj.cartservice.service;

import com.fourj.cartservice.dto.AddItemRequest;
import com.fourj.cartservice.dto.CartDto;
import com.fourj.cartservice.dto.CartItemDto;
import com.fourj.cartservice.dto.UpdateItemRequest;

public interface CartService {
    CartDto getCartByUserId(String userId);
    CartDto addItemToCart(String userId, AddItemRequest request);
    CartDto updateCartItem(String userId, Long itemId, UpdateItemRequest request);
    CartDto removeItemFromCart(String userId, Long itemId);
    void clearCart(String userId);
    void restoreCart(String userId, CartDto cartDto);
}