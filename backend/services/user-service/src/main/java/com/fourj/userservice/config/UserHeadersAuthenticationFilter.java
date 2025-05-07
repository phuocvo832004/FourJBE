package com.fourj.userservice.config;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Filter để xử lý xác thực thông qua headers từ Kong OIDC
 * Thay thế JWT validation
 */
public class UserHeadersAuthenticationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
            throws ServletException, IOException {
        
        // Lấy thông tin từ các headers được Kong-OIDC gửi xuống
        String userId = request.getHeader("X-User-ID");
        String userInfoHeader = request.getHeader("X-Userinfo");
        String permissionsHeader = request.getHeader("X-User-Permissions");
        
        if (userId != null && !userId.isEmpty()) {
            // Tạo map để lưu thông tin của user giống như JWT claims
            Map<String, Object> userAttributes = new HashMap<>();
            userAttributes.put("sub", userId);
            
            // Parse thông tin user từ header nếu có
            if (userInfoHeader != null && !userInfoHeader.isEmpty()) {
                try {
                    Map<String, Object> userInfo = objectMapper.readValue(userInfoHeader, Map.class);
                    userAttributes.putAll(userInfo);
                } catch (Exception e) {
                    logger.warn("Could not parse X-Userinfo header: " + e.getMessage());
                }
            }
            
            // Tạo danh sách quyền từ header permissions
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (permissionsHeader != null && !permissionsHeader.isEmpty()) {
                try {
                    String[] permissions = permissionsHeader.split(",");
                    authorities = Stream.of(permissions)
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());
                } catch (Exception e) {
                    logger.warn("Could not parse X-User-Permissions header: " + e.getMessage());
                }
            }
            
            // Thêm phân quyền dựa trên giá trị từ thông tin userInfo
            if (userAttributes.containsKey("roles")) {
                Object roles = userAttributes.get("roles");
                if (roles instanceof List) {
                    List<SimpleGrantedAuthority> finalAuthorities = authorities;
                    ((List<?>) roles).forEach(role -> {
                        if (role != null) {
                            String roleStr = role.toString();
                            if (roleStr.equals("admin")) {
                                finalAuthorities.add(new SimpleGrantedAuthority("admin:access"));
                            } else if (roleStr.equals("seller")) {
                                finalAuthorities.add(new SimpleGrantedAuthority("seller:access"));
                            }
                        }
                    });
                }
            }
            
            // Tạo đối tượng xác thực với thông tin người dùng
            KongUser kongUser = new KongUser(userId, userAttributes);
            UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(kongUser, null, authorities);
            
            // Đặt vào SecurityContext để Spring Security sử dụng
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        
        filterChain.doFilter(request, response);
    }

    /**
     * Lớp đại diện cho người dùng đã xác thực từ Kong OIDC
     * Có thể truy cập thông qua Principal trong controller
     */
    @Getter
    public static class KongUser {
        private final String id;
        private final Map<String, Object> attributes;

        public KongUser(String id, Map<String, Object> attributes) {
            this.id = id;
            this.attributes = attributes;
        }

        public Object getAttribute(String name) {
            return attributes.get(name);
        }
    }
} 