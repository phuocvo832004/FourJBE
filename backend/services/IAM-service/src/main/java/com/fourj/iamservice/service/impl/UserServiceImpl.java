// backend/services/IAM-service/src/main/java/com/fourj/iamservice/service/impl/UserServiceImpl.java
package com.fourj.iamservice.service.impl;

import com.fourj.iamservice.dto.RoleDto;
import com.fourj.iamservice.dto.UserDto;
import com.fourj.iamservice.dto.UserUpdateDto;
import com.fourj.iamservice.model.User;
import com.fourj.iamservice.model.UserRole;
import com.fourj.iamservice.repository.UserRepository;
import com.fourj.iamservice.repository.UserRoleRepository;
import com.fourj.iamservice.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    @Autowired
    public UserServiceImpl(UserRepository userRepository, UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Override
    @Transactional
    public UserDto createUser(User user) {
        User savedUser = userRepository.save(user);
        return mapUserToUserDto(savedUser);
    }

    @Override
    public UserDto getUserByAuth0Id(String auth0Id) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return mapUserToUserDto(user);
    }

    @Override
    @Transactional
    public UserDto updateUser(String auth0Id, UserUpdateDto userUpdateDto) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (userUpdateDto.getFullName() != null) {
            user.setDisplayName(userUpdateDto.getFullName());
        }
        
        if (userUpdateDto.getPhone() != null) {
            user.setPhone(userUpdateDto.getPhone());
        }
        
        if (userUpdateDto.getAddress() != null) {
            user.setAddress(userUpdateDto.getAddress());
        }
        
        if (userUpdateDto.getAvatarUrl() != null) {
            user.setAvatarUrl(userUpdateDto.getAvatarUrl());
        }
        
        if (userUpdateDto.getActive() != null) {
            user.setActive(userUpdateDto.getActive());
        }

        User updatedUser = userRepository.save(user);
        return mapUserToUserDto(updatedUser);
    }

    @Override
    public List<UserDto> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::mapUserToUserDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteUser(String auth0Id) {
        User user = userRepository.findByAuth0Id(auth0Id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        userRepository.delete(user);
    }

    @Override
    public boolean userExists(String auth0Id) {
        return userRepository.existsByAuth0Id(auth0Id);
    }

    private UserDto mapUserToUserDto(User user) {
        Set<RoleDto> roleDtos = user.getUserRoles().stream()
                .map(UserRole::getRole)
                .map(role -> new RoleDto(role.getId(), role.getName()))
                .collect(Collectors.toSet());

        return UserDto.builder()
                .id(user.getId())
                .auth0Id(user.getAuth0Id())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .phone(user.getPhone())
                .address(user.getAddress())
                .avatarUrl(user.getAvatarUrl())
                .active(user.isActive())
                .roles(roleDtos)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}