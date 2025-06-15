package com.fourj.iamservice.service;

import com.fourj.iamservice.dto.RoleDto;
import com.fourj.iamservice.dto.UserPermissionsDto;
import com.fourj.iamservice.dto.VerifyPermissionRequestDto;
import com.fourj.iamservice.dto.VerifyPermissionResponseDto;
import com.fourj.iamservice.exception.ResourceNotFoundException;
import com.fourj.iamservice.model.Permission;
import com.fourj.iamservice.model.Role;
import com.fourj.iamservice.model.User;
import com.fourj.iamservice.model.UserRole;
import com.fourj.iamservice.repository.RoleRepository;
import com.fourj.iamservice.repository.UserRepository;
import com.fourj.iamservice.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public abstract class UserRoleService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserPermissionsDto getUserPermissions(String auth0Id) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        List<UserRole> userRoles = userRoleRepository.findByUser(user);

        List<String> roleNames = userRoles.stream()
                .map(userRole -> userRole.getRole().getName())
                .collect(Collectors.toList());

        Set<String> permissions = new HashSet<>();

        userRoles.forEach(userRole -> {
            Role role = userRole.getRole();
            Set<String> rolePermissions = role.getPermissions().stream()
                    .map(Permission::getName)
                    .collect(Collectors.toSet());
            permissions.addAll(rolePermissions);
        });

        return UserPermissionsDto.builder()
                .userId(auth0Id)
                .roles(roleNames)
                .permissions(permissions)
                .build();
    }

    @Transactional
    public void assignRoleToUser(String auth0Id, String roleName) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleName));

        // Check if assignment already exists
        boolean exists = userRoleRepository.existsByUserAndRole(user, role);

        if (!exists) {
            UserRole userRole = UserRole.builder()
                    .user(user)
                    .role(role)
                    .build();
            userRoleRepository.save(userRole);
        }
    }

    @Transactional
    public VerifyPermissionResponseDto verifyPermissions(VerifyPermissionRequestDto requestDto) {
        UserPermissionsDto userPermissions = getUserPermissions(requestDto.getUserId());

        boolean hasAllPermissions = userPermissions.getPermissions().containsAll(requestDto.getRequiredPermissions());

        if (hasAllPermissions) {
            return VerifyPermissionResponseDto.builder()
                    .allowed(true)
                    .message("User has all required permissions")
                    .build();
        } else {
            return VerifyPermissionResponseDto.builder()
                    .allowed(false)
                    .message("User lacks required permissions")
                    .build();
        }
    }

    @Transactional(readOnly = true)
    public abstract List<RoleDto> getUserRoles(String auth0Id);

    @Transactional
    public abstract void assignRoleToUser(String auth0Id, Long roleId);

    @Transactional
    public abstract void removeRoleFromUser(String auth0Id, Long roleId);

    @Transactional(readOnly = true)
    public abstract boolean hasRole(String auth0Id, String roleName);
}