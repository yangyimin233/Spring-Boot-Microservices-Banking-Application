package org.training.user.service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.training.user.service.exception.ResourceConflictException;
import org.training.user.service.model.Status;
import org.training.user.service.model.dto.CreateUser;
import org.training.user.service.model.dto.LoginRequest;
import org.training.user.service.model.dto.LoginResponse;
import org.training.user.service.model.dto.response.Response;
import org.training.user.service.model.entity.User;
import org.training.user.service.model.entity.UserProfile;
import org.training.user.service.repository.UserRepository;
import org.training.user.service.security.JwtTokenProvider;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/register")
    public ResponseEntity<Response> register(@RequestBody CreateUser userDto) {
        Optional<User> existing = userRepository.findUserByEmailId(userDto.getEmailId());
        if (existing.isPresent()) {
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
        return ResponseEntity.ok(Response.builder()
                .responseMessage("User created successfully")
                .responseCode("200").build());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        User user = userRepository.findUserByEmailId(loginRequest.getEmailId())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }

        String token = jwtTokenProvider.generateToken(user.getAuthId(), user.getUserId(), user.getEmailId());

        return ResponseEntity.ok(LoginResponse.builder()
                .token(token)
                .userId(user.getUserId())
                .email(user.getEmailId())
                .build());
    }
}
