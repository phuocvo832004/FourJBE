package com.fourj.cartservice.service.impl;

import com.fourj.cartservice.dto.*;
import com.fourj.cartservice.exception.ProductNotFoundException;
import com.fourj.cartservice.exception.ResourceNotFoundException;
import com.fourj.cartservice.model.Cart;
import com.fourj.cartservice.model.CartItem;
import com.fourj.cartservice.repository.CartItemRepository;
import com.fourj.cartservice.repository.CartRepository;
import com.fourj.cartservice.service.CartService;
import com.fourj.cartservice.service.ProductClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductClient productClient;

    @Autowired
    public CartServiceImpl(CartRepository cartRepository,
                           CartItemRepository cartItemRepository,
                           ProductClient productClient) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productClient = productClient;
    }

    @Override
    public CartDto getCartByUserId(String userId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setUserId(userId);
                    return cartRepository.save(newCart);
                });

        return mapToDto(cart);
    }

    @Override
    @Transactional
    public CartDto addItemToCart(String userId, AddItemRequest request) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setUserId(userId);
                    return cartRepository.save(newCart);
                });

        // Lấy thông tin sản phẩm từ Product Service
        ProductDto product = productClient.getProductById(request.getProductId())
                .blockOptional()
                .orElseThrow(() -> new ProductNotFoundException("Không tìm thấy sản phẩm với ID: " + request.getProductId()));

        if (!product.isActive()) {
            throw new ProductNotFoundException("Sản phẩm đã bị vô hiệu hóa: " + request.getProductId());
        }

        // Kiểm tra sản phẩm đã có trong giỏ hàng chưa
        CartItem cartItem = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.getProductId())
                .orElse(null);

        if (cartItem != null) {
            // Cập nhật số lượng nếu sản phẩm đã có trong giỏ hàng
            cartItem.setQuantity(cartItem.getQuantity() + request.getQuantity());
        } else {
            // Thêm mới nếu sản phẩm chưa có trong giỏ hàng
            cartItem = new CartItem();
            cartItem.setCart(cart);
            cartItem.setProductId(request.getProductId());
            cartItem.setProductName(product.getName());
            cartItem.setProductImage(product.getImageUrl());
            cartItem.setPrice(product.getPrice());
            cartItem.setQuantity(request.getQuantity());
            cart.getItems().add(cartItem);
        }

        cartItemRepository.save(cartItem);
        cart.recalculateTotalPrice();
        Cart updatedCart = cartRepository.save(cart);

        return mapToDto(updatedCart);
    }

    @Override
    @Transactional
    public CartDto updateCartItem(String userId, Long itemId, UpdateItemRequest request) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Giỏ hàng không tồn tại cho người dùng: " + userId));

        CartItem cartItem = cart.getItems().stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm trong giỏ hàng"));

        cartItem.setQuantity(request.getQuantity());
        cartItemRepository.save(cartItem);

        cart.recalculateTotalPrice();
        Cart updatedCart = cartRepository.save(cart);

        return mapToDto(updatedCart);
    }

    @Override
    @Transactional
    public CartDto removeItemFromCart(String userId, Long itemId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Giỏ hàng không tồn tại cho người dùng: " + userId));

        CartItem cartItem = cart.getItems().stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm trong giỏ hàng"));

        cart.getItems().remove(cartItem);
        cartItemRepository.deleteById(itemId);

        cart.recalculateTotalPrice();
        Cart updatedCart = cartRepository.save(cart);

        return mapToDto(updatedCart);
    }

    @Override
    @Transactional
    public void clearCart(String userId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Giỏ hàng không tồn tại cho người dùng: " + userId));

        cart.getItems().clear();
        cart.setTotalPrice(java.math.BigDecimal.ZERO);
        cartRepository.save(cart);
    }

    @Override
    @Transactional
    public void restoreCart(String userId, CartDto cartDto) {
        // Xóa giỏ hàng hiện tại nếu có
        Cart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setUserId(userId);
                    return cartRepository.save(newCart);
                });
        
        // Xóa tất cả các item hiện tại
        cartItemRepository.deleteAll(cart.getItems());
        cart.getItems().clear();
        
        // Thêm lại các item từ cartDto
        for (CartItemDto itemDto : cartDto.getItems()) {
            CartItem cartItem = new CartItem();
            cartItem.setCart(cart);
            cartItem.setProductId(itemDto.getProductId());
            cartItem.setProductName(itemDto.getProductName());
            cartItem.setProductImage(itemDto.getProductImage());
            cartItem.setPrice(itemDto.getPrice());
            cartItem.setQuantity(itemDto.getQuantity());
            cart.getItems().add(cartItem);
            cartItemRepository.save(cartItem);
        }
        
        cart.recalculateTotalPrice();
        cartRepository.save(cart);
    }

    private CartDto mapToDto(Cart cart) {
        List<CartItemDto> itemDtos = cart.getItems().stream()
                .map(item -> CartItemDto.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .productImage(item.getProductImage())
                        .price(item.getPrice())
                        .quantity(item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        return CartDto.builder()
                .id(cart.getId())
                .userId(cart.getUserId())
                .items(itemDtos)
                .totalPrice(cart.getTotalPrice())
                .createdAt(cart.getCreatedAt())
                .updatedAt(cart.getUpdatedAt())
                .build();
    }
}