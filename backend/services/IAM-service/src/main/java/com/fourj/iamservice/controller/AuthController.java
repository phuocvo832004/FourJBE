package com.fourj.iamservice.controller;

import com.fourj.iamservice.dto.TokenRequestDto;
import com.fourj.iamservice.dto.TokenResponseDto;
import com.fourj.iamservice.service.TokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final TokenService tokenService;
    
    @PostMapping("/token")
    public ResponseEntity<TokenResponseDto> getToken(
            @RequestBody TokenRequestDto tokenRequest,
            HttpServletResponse response) {
            
        TokenResponseDto tokenResponse = tokenService.getToken(tokenRequest);
        
        // Lưu refresh token trong HttpOnly cookie
        if (tokenResponse.getRefreshToken() != null) {
            Cookie refreshTokenCookie = new Cookie("refresh_token", tokenResponse.getRefreshToken());
            refreshTokenCookie.setHttpOnly(true);
            refreshTokenCookie.setSecure(true);
            refreshTokenCookie.setPath("/auth");
            refreshTokenCookie.setMaxAge(30 * 24 * 60 * 60); // 30 ngày
            refreshTokenCookie.setAttribute("SameSite", "Strict");
            response.addCookie(refreshTokenCookie);
            
            // Không trả về refresh token trong response
            tokenResponse.setRefreshToken(null);
        }
        
        return ResponseEntity.ok(tokenResponse);
    }
    
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponseDto> refreshToken(
            HttpServletRequest request,
            HttpServletResponse response) {
            
        // Lấy refresh token từ cookie
        String refreshToken = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("refresh_token".equals(cookie.getName())) {
                    refreshToken = cookie.getValue();
                    break;
                }
            }
        }
        
        if (refreshToken == null) {
            return ResponseEntity.badRequest().build();
        }
        
        TokenRequestDto tokenRequest = new TokenRequestDto();
        tokenRequest.setGrantType("refresh_token");
        tokenRequest.setRefreshToken(refreshToken);
        
        TokenResponseDto tokenResponse = tokenService.refreshToken(tokenRequest);
        
        // Cập nhật refresh token mới vào cookie (rotation)
        if (tokenResponse.getRefreshToken() != null) {
            Cookie refreshTokenCookie = new Cookie("refresh_token", tokenResponse.getRefreshToken());
            refreshTokenCookie.setHttpOnly(true);
            refreshTokenCookie.setSecure(true);
            refreshTokenCookie.setPath("/auth");
            refreshTokenCookie.setMaxAge(30 * 24 * 60 * 60); // 30 ngày
            refreshTokenCookie.setAttribute("SameSite", "Strict");
            response.addCookie(refreshTokenCookie);
            
            // Không trả về refresh token trong response
            tokenResponse.setRefreshToken(null);
        }
        
        return ResponseEntity.ok(tokenResponse);
    }
    
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        // Xóa refresh token cookie
        Cookie refreshTokenCookie = new Cookie("refresh_token", "");
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setSecure(true);
        refreshTokenCookie.setPath("/auth");
        refreshTokenCookie.setMaxAge(0);
        response.addCookie(refreshTokenCookie);
        
        return ResponseEntity.ok().build();
    }
} 