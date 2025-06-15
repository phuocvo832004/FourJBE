package com.fourj.iamservice.controller;

import com.fourj.iamservice.dto.UserPermissionsDto;
import com.fourj.iamservice.dto.VerifyPermissionRequestDto;
import com.fourj.iamservice.dto.VerifyPermissionResponseDto;
import com.fourj.iamservice.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/user-roles")
@RequiredArgsConstructor
@Slf4j
public class UserRoleController {

    private final UserRoleService userRoleService;

    @GetMapping("/me/user-permissions")
    public ResponseEntity<UserPermissionsDto> getCurrentUserPermissions(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(userRoleService.getUserPermissions(userId));
    }

    @GetMapping("/{userId}/permissions")
    @PreAuthorize("hasAuthority('admin:access')")
    public ResponseEntity<UserPermissionsDto> getUserPermissions(@PathVariable String userId) {
        return ResponseEntity.ok(userRoleService.getUserPermissions(userId));
    }

    @PostMapping("/{userId}/roles/{roleName}")
    @PreAuthorize("hasAuthority('admin:access')")
    public ResponseEntity<Void> assignRoleToUser(@PathVariable String userId, @PathVariable String roleName) {
        userRoleService.assignRoleToUser(userId, roleName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/verify-permissions")
    public ResponseEntity<VerifyPermissionResponseDto> verifyPermissions(@RequestBody VerifyPermissionRequestDto requestDto) {
        return ResponseEntity.ok(userRoleService.verifyPermissions(requestDto));
    }
}