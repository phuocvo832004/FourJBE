package com.fourj.iamservice.controller;

import com.fourj.iamservice.dto.RoleDto;
import com.fourj.iamservice.dto.UserPermissionsDto;
import com.fourj.iamservice.service.UserRoleService;
import com.fourj.iamservice.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Set;

@RestController

@Slf4j
public class AuthorizationController {

    private final UserService userService;
    private final UserRoleService userRoleService;

    @Autowired
    public AuthorizationController(UserService userService, UserRoleService userRoleService) {
        this.userService = userService;
        this.userRoleService = userRoleService;
    }

    @GetMapping("/roles")
    public ResponseEntity<Set<RoleDto>> getUserRoles(@AuthenticationPrincipal Jwt jwt) {
        try {
            String auth0Id = jwt.getSubject();
            if (!userService.userExists(auth0Id)) {
                // Trả về danh sách trống nếu người dùng chưa tồn tại
                log.warn("Yêu cầu vai trò cho người dùng không tồn tại: {}", auth0Id);
                return ResponseEntity.ok(Set.of());
            }
            return ResponseEntity.ok(userService.getUserByAuth0Id(auth0Id).getRoles());
        } catch (Exception e) {
            log.error("Lỗi khi lấy vai trò người dùng", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể lấy vai trò người dùng", e);
        }
    }

    @GetMapping("/permissions")
    public ResponseEntity<UserPermissionsDto> getUserPermissions(@AuthenticationPrincipal Jwt jwt) {
        try {
            String userId = jwt.getSubject();
            return ResponseEntity.ok(userRoleService.getUserPermissions(userId));
        } catch (Exception e) {
            log.error("Lỗi khi lấy quyền người dùng", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể lấy quyền người dùng", e);
        }
    }
}