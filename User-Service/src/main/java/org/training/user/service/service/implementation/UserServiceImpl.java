package org.training.user.service.service.implementation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.training.user.service.exception.EmptyFields;
import org.training.user.service.exception.ResourceConflictException;
import org.training.user.service.exception.ResourceNotFound;
import org.training.user.service.external.AccountService;
import org.training.user.service.model.Status;
import org.training.user.service.model.dto.CreateUser;
import org.training.user.service.model.dto.UserDto;
import org.training.user.service.model.dto.UserUpdate;
import org.training.user.service.model.dto.UserUpdateStatus;
import org.training.user.service.model.dto.response.Response;
import org.training.user.service.model.entity.User;
import org.training.user.service.model.entity.UserProfile;
import org.training.user.service.model.external.Account;
import org.training.user.service.model.mapper.UserMapper;
import org.training.user.service.repository.UserRepository;
import org.training.user.service.service.UserService;
import org.training.user.service.utils.FieldChecker;

import javax.transaction.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;

    private UserMapper userMapper = new UserMapper();

    @Value("${spring.application.success}")
    private String responseCodeSuccess;

    @Value("${spring.application.not_found}")
    private String responseCodeNotFound;

    @Override
    public Response createUser(CreateUser userDto) {
        // Check if email is already registered
        if (userRepository.findUserByEmailId(userDto.getEmailId()).isPresent()) {
            log.error("This emailId is already registered as a user");
            throw new ResourceConflictException("This emailId is already registered as a user");
        }

        UserProfile userProfile = UserProfile.builder()
                .firstName(userDto.getFirstName())
                .lastName(userDto.getLastName()).build();

        User user = User.builder()
                .emailId(userDto.getEmailId())
                .password(passwordEncoder.encode(userDto.getPassword()))
                .contactNo(userDto.getContactNumber())
                .status(Status.PENDING)
                .userProfile(userProfile)
                .authId(UUID.randomUUID().toString())
                .identificationNumber(UUID.randomUUID().toString()).build();

        userRepository.save(user);
        return Response.builder()
                .responseMessage("User created successfully")
                .responseCode(responseCodeSuccess).build();
    }

    @Override
    public List<UserDto> readAllUsers() {
        return userRepository.findAll().stream()
                .map(user -> {
                    UserDto userDto = userMapper.convertToDto(user);
                    userDto.setEmailId(user.getEmailId());
                    userDto.setIdentificationNumber(user.getIdentificationNumber());
                    return userDto;
                }).collect(Collectors.toList());
    }

    @Override
    public UserDto readUser(String authId) {
        User user = userRepository.findUserByAuthId(authId)
                .orElseThrow(() -> new ResourceNotFound("User not found on the server"));

        UserDto userDto = userMapper.convertToDto(user);
        userDto.setEmailId(user.getEmailId());
        return userDto;
    }

    @Override
    public Response updateUserStatus(Long id, UserUpdateStatus userUpdate) {
        User user = userRepository.findById(id).orElseThrow(
                () -> new ResourceNotFound("User not found on the server"));

        if (FieldChecker.hasEmptyFields(user)) {
            log.error("User is not updated completely");
            throw new EmptyFields("please update the user", responseCodeNotFound);
        }

        user.setStatus(userUpdate.getStatus());
        userRepository.save(user);

        return Response.builder()
                .responseMessage("User updated successfully")
                .responseCode(responseCodeSuccess).build();
    }

    @Override
    public UserDto readUserById(Long userId) {
        return userRepository.findById(userId)
                .map(user -> userMapper.convertToDto(user))
                .orElseThrow(() -> new ResourceNotFound("User not found on the server"));
    }

    @Override
    public Response updateUser(Long id, UserUpdate userUpdate) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFound("User not found on the server"));

        user.setContactNo(userUpdate.getContactNo());
        BeanUtils.copyProperties(userUpdate, user.getUserProfile());
        userRepository.save(user);

        return Response.builder()
                .responseCode(responseCodeSuccess)
                .responseMessage("user updated successfully").build();
    }

    @Override
    public UserDto readUserByAccountId(String accountId) {
        ResponseEntity<Account> response = accountService.readByAccountNumber(accountId);
        if (Objects.isNull(response.getBody())) {
            throw new ResourceNotFound("account not found on the server");
        }
        Long userId = response.getBody().getUserId();
        return userRepository.findById(userId)
                .map(user -> userMapper.convertToDto(user))
                .orElseThrow(() -> new ResourceNotFound("User not found on the server"));
    }
}
