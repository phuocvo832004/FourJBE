package com.fourj.iamservice.service;

import com.fourj.iamservice.dto.PermissionDto;
import com.fourj.iamservice.dto.RoleDto;
import com.fourj.iamservice.model.Permission;
import com.fourj.iamservice.model.Role;
import com.fourj.iamservice.model.User;
import com.fourj.iamservice.model.UserRole;
import com.fourj.iamservice.repository.PermissionRepository;
import com.fourj.iamservice.repository.RoleRepository;
import com.fourj.iamservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Transactional(readOnly = true)
    public List<PermissionDto> getAllPermissions() {
        return permissionRepository.findAll().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public PermissionDto createPermission(PermissionDto permissionDto) {
        Permission permission = Permission.builder()
                .name(permissionDto.getName())
                .description(permissionDto.getDescription())
                .resource(permissionDto.getResource())
                .build();

        Permission savedPermission = permissionRepository.save(permission);
        return mapToDto(savedPermission);
    }

    @Transactional(readOnly = true)
    public boolean userHasPermission(String auth0Id, String permissionName) {
        Optional<User> userOptional = userRepository.findByAuth0Id(auth0Id);

        if (userOptional.isEmpty()) {
            return false;
        }

        User user = userOptional.get();
        Set<UserRole> userRoles = user.getUserRoles();

        return userRoles.stream()
                .map(UserRole::getRole)
                .flatMap(role -> role.getPermissions().stream())
                .anyMatch(permission -> permission.getName().equals(permissionName));
    }

    @Transactional(readOnly = true)
    public Set<PermissionDto> getUserPermissions(String auth0Id) {
        Optional<User> userOptional = userRepository.findByAuth0Id(auth0Id);

        if (userOptional.isEmpty()) {
            return Set.of();
        }

        User user = userOptional.get();

        return user.getUserRoles().stream()
                .map(UserRole::getRole)
                .flatMap(role -> role.getPermissions().stream())
                .map(this::mapToDto)
                .collect(Collectors.toSet());
    }

    private PermissionDto mapToDto(Permission permission) {
        return PermissionDto.builder()
                .id(permission.getId())
                .name(permission.getName())
                .description(permission.getDescription())
                .resource(permission.getResource())
                .build();
    }
}