package com.fourj.iamservice.service.impl;

import com.fourj.iamservice.dto.RoleDto;
import com.fourj.iamservice.model.Role;
import com.fourj.iamservice.model.User;
import com.fourj.iamservice.model.UserRole;
import com.fourj.iamservice.repository.RoleRepository;
import com.fourj.iamservice.repository.UserRepository;
import com.fourj.iamservice.repository.UserRoleRepository;
import com.fourj.iamservice.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserRoleServiceImpl extends UserRoleService {

    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Autowired
    public UserRoleServiceImpl(UserRoleRepository userRoleRepository, RoleRepository roleRepository, UserRepository userRepository) {
        super(userRoleRepository, roleRepository, userRepository);
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    @Transactional(readOnly = true)
    @Override
    public List<RoleDto> getUserRoles(String auth0Id) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return userRoleRepository.findByUser(user).stream()
                .map(UserRole::getRole)
                .map(this::mapToRoleDto)
                .collect(Collectors.toList());
    }

    @Transactional
    @Override
    public void assignRoleToUser(String auth0Id, Long roleId) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found"));
        
        if (!userRoleRepository.existsByUserAndRole(user, role)) {
            UserRole userRole = UserRole.builder()
                    .user(user)
                    .role(role)
                    .build();
            
            userRoleRepository.save(userRole);
        }
    }

    @Transactional
    @Override
    public void removeRoleFromUser(String auth0Id, Long roleId) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found"));
        
        userRoleRepository.findByUserAndRole(user, role)
                .ifPresent(userRoleRepository::delete);
    }

    @Transactional(readOnly = true)
    @Override
    public boolean hasRole(String auth0Id, String roleName) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new RuntimeException("Role not found"));
        
        return userRoleRepository.existsByUserAndRole(user, role);
    }

    private RoleDto mapToRoleDto(Role role) {
        return RoleDto.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .build();
    }
}