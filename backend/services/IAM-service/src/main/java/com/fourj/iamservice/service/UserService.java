package com.fourj.iamservice.service;

import com.fourj.iamservice.dto.UserDto;
import com.fourj.iamservice.dto.UserUpdateDto;
import com.fourj.iamservice.model.User;

import java.util.List;

public interface UserService {
    UserDto createUser(User user);
    UserDto getUserByAuth0Id(String auth0Id);
    UserDto updateUser(String auth0Id, UserUpdateDto userUpdateDto);
    List<UserDto> getAllUsers();
    void deleteUser(String auth0Id);
    boolean userExists(String auth0Id);
}

