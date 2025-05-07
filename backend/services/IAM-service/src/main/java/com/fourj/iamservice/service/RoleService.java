package com.fourj.iamservice.service;
import com.fourj.iamservice.dto.PermissionDto;
import com.fourj.iamservice.dto.RoleDto;
import com.fourj.iamservice.model.Permission;
import com.fourj.iamservice.model.Role;
import com.fourj.iamservice.repository.PermissionRepository;
import com.fourj.iamservice.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    @Transactional(readOnly = true)
    public List<RoleDto> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public RoleDto createRole(RoleDto roleDto) {
        Set<Permission> permissions = new HashSet<>();

        if (roleDto.getPermissions() != null) {
            permissions = roleDto.getPermissions().stream()
                    .map(permDto -> permissionRepository.findByName(permDto.getName())
                            .orElseThrow(() -> new RuntimeException("Permission not found: " + permDto.getName())))
                    .collect(Collectors.toSet());
        }

        Role role = Role.builder()
                .name(roleDto.getName())
                .description(roleDto.getDescription())
                .permissions(permissions)
                .build();

        Role savedRole = roleRepository.save(role);
        return mapToDto(savedRole);
    }

    @Transactional(readOnly = true)
    public Optional<RoleDto> getRoleById(Long id) {
        return roleRepository.findById(id)
                .map(this::mapToDto);
    }

    @Transactional(readOnly = true)
    public Optional<RoleDto> getRoleByName(String name){
        return roleRepository.findByName(name).map(this::mapToDto);
    }

    private RoleDto mapToDto(Role role) {
        Set<PermissionDto> permissionDtos = role.getPermissions().stream()
                .map(permission -> PermissionDto.builder()
                        .id(permission.getId())
                        .name(permission.getName())
                        .description(permission.getDescription())
                        .resource(permission.getResource())
                        .build())
                .collect(Collectors.toSet());

        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(permissionDtos)
                .build();
    }
}