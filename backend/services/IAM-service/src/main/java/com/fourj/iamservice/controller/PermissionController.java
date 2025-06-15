package com.fourj.iamservice.controller;

import com.fourj.iamservice.dto.PermissionDto;
import com.fourj.iamservice.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@RestController
@RequestMapping("/admin/permissions")
@RequiredArgsConstructor
@Slf4j
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    @PreAuthorize("hasAuthority('admin:access')")
    public ResponseEntity<List<PermissionDto>> getAllPermissions() {
        return ResponseEntity.ok(permissionService.getAllPermissions());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('admin:access')")
    public ResponseEntity<PermissionDto> createPermission(@RequestBody PermissionDto permissionDto) {
        return new ResponseEntity<>(permissionService.createPermission(permissionDto), HttpStatus.CREATED);
    }
}