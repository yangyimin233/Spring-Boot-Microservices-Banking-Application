package org.training.transactions.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.training.transactions.configuration.FeignClientConfiguration;
import org.training.transactions.model.dto.UserDto;

@FeignClient(name = "user-service", configuration = FeignClientConfiguration.class)
public interface UserService {

    @GetMapping("/api/users/{userId}")
    ResponseEntity<UserDto> readUserById(@PathVariable Long userId);
}
