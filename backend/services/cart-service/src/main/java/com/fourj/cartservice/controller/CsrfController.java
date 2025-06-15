package com.fourj.cartservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sanctum")
public class CsrfController {

    @GetMapping("/csrf-cookie")
    public ResponseEntity<Void> getCsrfCookie() {
        // Spring Security tự động gắn CSRF cookie khi có yêu cầu đến endpoint này
        return ResponseEntity.ok().build();
    }
} 