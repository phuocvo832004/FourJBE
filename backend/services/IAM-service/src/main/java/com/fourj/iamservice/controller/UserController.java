// backend/services/IAM-service/src/main/java/com/fourj/iamservice/controller/UserController.java
package com.fourj.iamservice.controller;

import com.fourj.iamservice.dto.UserDto;
import com.fourj.iamservice.dto.UserPermissionsDto;
import com.fourj.iamservice.dto.UserUpdateDto;
import com.fourj.iamservice.model.User;
import com.fourj.iamservice.service.UserRoleService;
import com.fourj.iamservice.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
@Slf4j
public class UserController {

    private final UserService userService;
    private final UserRoleService userRoleService;

    @Autowired
    public UserController(UserService userService, UserRoleService userRoleService) {
        this.userService = userService;
        this.userRoleService = userRoleService;
    }

    @GetMapping("/me/user-permissions")
    public ResponseEntity<UserPermissionsDto> getCurrentUserPermissions(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(userRoleService.getUserPermissions(userId));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String auth0Id = jwt.getSubject();

        // Kiểm tra xem người dùng đã tồn tại chưa
        if (!userService.userExists(auth0Id)) {
            // Tạo người dùng mới nếu chưa tồn tại
            User newUser = new User();
            newUser.setAuth0Id(auth0Id);
            newUser.setActive(true);

            // Lấy email từ JWT token claims
            String email = jwt.getClaim("email");
            
            // Bổ sung kiểm tra email từ các vị trí khác trong token
            if (email == null || email.isEmpty()) {
                Map<String, Object> claims = jwt.getClaims();
                
                // Tìm email trong các thuộc tính phổ biến khác của JWT
                if (claims.containsKey("emails") && claims.get("emails") instanceof List) {
                    List<?> emails = (List<?>) claims.get("emails");
                    if (!emails.isEmpty() && emails.get(0) != null) {
                        email = emails.get(0).toString();
                    }
                } else if (claims.containsKey("preferred_username")) {
                    email = (String) claims.get("preferred_username");
                } else if (claims.containsKey("nickname")) {
                    String nickname = (String) claims.get("nickname");
                    if (nickname != null && nickname.contains("@")) {
                        email = nickname;
                    }
                }
            }
            
            // Kiểm tra nếu vẫn không có email, sử dụng một giá trị mặc định
            if (email == null || email.isEmpty()) {
                // Tạo email dựa trên auth0Id
                String provider = auth0Id.contains("|") ? auth0Id.split("\\|")[0] : "auth0";
                String userId = auth0Id.contains("|") ? auth0Id.split("\\|")[1] : auth0Id;
                email = userId + "@" + provider + ".user";
            }
            
            newUser.setEmail(email);

            // Lấy displayName từ JWT token claims (nếu có)
            String name = jwt.getClaim("name");
            if (name == null || name.isEmpty()) {
                name = jwt.getClaim("given_name");
                
                if (name == null || name.isEmpty()) {
                    name = jwt.getClaim("nickname");
                }
                
                if (name == null || name.isEmpty()) {
                    // Nếu không có tên, lấy từ phần đầu email
                    if (email.contains("@")) {
                        name = email.substring(0, email.indexOf('@'));
                    } else {
                        name = "User";
                    }
                }
            }
            
            newUser.setDisplayName(name);

            try {
                return new ResponseEntity<>(userService.createUser(newUser), HttpStatus.CREATED);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, 
                    "Không thể tạo người dùng: " + e.getMessage(), e);
            }
        }

        return ResponseEntity.ok(userService.getUserByAuth0Id(auth0Id));
    }

    @PutMapping("/me")
    public ResponseEntity<UserDto> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UserUpdateDto userUpdateDto) {
        String auth0Id = jwt.getSubject();
        return ResponseEntity.ok(userService.updateUser(auth0Id, userUpdateDto));
    }

    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{auth0Id}")
    public ResponseEntity<UserDto> getUserByAuth0Id(@PathVariable String auth0Id) {
        return ResponseEntity.ok(userService.getUserByAuth0Id(auth0Id));
    }

    @DeleteMapping("/{auth0Id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String auth0Id) {
        userService.deleteUser(auth0Id);
        return ResponseEntity.noContent().build();
    }
}