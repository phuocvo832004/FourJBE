package com.fourj.iamservice.controller;

import com.auth0.exception.Auth0Exception;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Response;
import com.fourj.iamservice.dto.RoleAssignmentDto;
import com.fourj.iamservice.exception.ResourceNotFoundException;
import com.fourj.iamservice.model.Role;
import com.fourj.iamservice.model.UserRole;
import com.fourj.iamservice.repository.RoleRepository;
import com.fourj.iamservice.repository.UserRepository;
import com.fourj.iamservice.repository.UserRoleRepository;
import com.fourj.iamservice.service.Auth0Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/role-assignments")
@Slf4j
public class RoleAssignmentController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final Auth0Service auth0Service;

    @Autowired
    public RoleAssignmentController(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            Auth0Service auth0Service) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.auth0Service = auth0Service;
    }

    @PostMapping
    public ResponseEntity<Void> assignRoleToUser(@RequestBody RoleAssignmentDto roleAssignment) {
        // Kiểm tra user tồn tại
        com.fourj.iamservice.model.User user = userRepository.findByAuth0Id(roleAssignment.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Role role = roleRepository.findById(roleAssignment.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        // Kiểm tra xem user đã có role này chưa
        boolean hasRole = userRoleRepository.existsByUserAndRole(user, role);
        if (!hasRole) {
            // Sử dụng Builder pattern để tạo đối tượng và set các thuộc tính
            UserRole userRole = UserRole.builder()
                    .user(user)
                    .role(role)
                    .build();

            userRoleRepository.save(userRole);

            // Cập nhật roles trong Auth0
            try {
                updateAuth0UserRoles(roleAssignment.getUserId(), role.getName());
            } catch (Auth0Exception e) {
                throw new RuntimeException("Failed to update Auth0 user roles", e);
            }
        }

        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> removeRoleFromUser(@RequestBody RoleAssignmentDto roleAssignment) {
        com.fourj.iamservice.model.User user = userRepository.findByAuth0Id(roleAssignment.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Role role = roleRepository.findById(roleAssignment.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        UserRole userRole = userRoleRepository.findByUserAndRole(user, role)
                .orElseThrow(() -> new ResourceNotFoundException("User does not have this role"));

        userRoleRepository.delete(userRole);

        // Cập nhật roles trong Auth0
        try {
            removeAuth0UserRole(roleAssignment.getUserId(), role.getName());
        } catch (Auth0Exception e) {
            throw new RuntimeException("Failed to update Auth0 user roles", e);
        }

        return ResponseEntity.ok().build();
    }

    private void updateAuth0UserRoles(String auth0Id, String roleName) throws Auth0Exception {
        Response<User> userResponse = auth0Service.getManagementAPI().users().get(auth0Id, null).execute();
        User auth0User = userResponse.getBody();

        List<Map<String, Object>> appMetadata = (List<Map<String, Object>>) auth0User.getAppMetadata().get("roles");

        if (appMetadata == null) {
            appMetadata = List.of(Map.of("name", roleName));
        } else {
            // Kiểm tra xem role đã tồn tại chưa
            boolean roleExists = appMetadata.stream()
                    .anyMatch(role -> roleName.equals(role.get("name")));

            if (!roleExists) {
                appMetadata.add(Map.of("name", roleName));
            }
        }

        // Tạo một Map hoàn chỉnh cho metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("roles", appMetadata);

        // Tạo và cấu hình user object riêng biệt
        User userToUpdate = new User();
        userToUpdate.setAppMetadata(metadata);

        // Thực hiện cập nhật
        auth0Service.getManagementAPI().users()
                .update(auth0Id, userToUpdate)
                .execute();
    }

    private void removeAuth0UserRole(String auth0Id, String roleName) throws Auth0Exception {
        Response<User> userResponse = auth0Service.getManagementAPI().users().get(auth0Id, null).execute();
        User auth0User = userResponse.getBody();

        List<Map<String, Object>> appMetadata = (List<Map<String, Object>>) auth0User.getAppMetadata().get("roles");

        if (appMetadata != null) {
            List<Map<String, Object>> updatedRoles = appMetadata.stream()
                    .filter(role -> !roleName.equals(role.get("name")))
                    .collect(Collectors.toList());

            // Tạo một Map hoàn chỉnh cho metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("roles", updatedRoles);

            // Tạo và cấu hình user object riêng biệt
            User userToUpdate = new User();
            userToUpdate.setAppMetadata(metadata);

            // Thực hiện cập nhật
            auth0Service.getManagementAPI().users()
                    .update(auth0Id, userToUpdate)
                    .execute();
        }
    }
}