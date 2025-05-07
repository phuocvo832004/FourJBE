package com.fourj.iamservice.service.impl;

import com.fourj.iamservice.dto.RoleDto;
import com.fourj.iamservice.model.Role;
import com.fourj.iamservice.repository.PermissionRepository;
import com.fourj.iamservice.repository.RoleRepository;
import com.fourj.iamservice.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RoleServiceImpl extends RoleService {

    private RoleRepository roleRepository;

    public RoleServiceImpl(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        super(roleRepository, permissionRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleDto> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public RoleDto createRole(RoleDto roleDto) {
        Role role = Role.builder()
                .name(roleDto.getName())
                .description(roleDto.getDescription())
                .build();

        Role savedRole = roleRepository.save(role);
        return mapToDto(savedRole);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<RoleDto> getRoleById(Long id) {
        return roleRepository.findById(id)
                .map(this::mapToDto);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<RoleDto> getRoleByName(String name) {
        return roleRepository.findByName(name)
                .map(this::mapToDto);
    }

    private RoleDto mapToDto(Role role) {
        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .build();
    }
}