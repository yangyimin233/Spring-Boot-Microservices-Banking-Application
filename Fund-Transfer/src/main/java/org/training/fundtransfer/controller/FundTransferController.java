package org.training.fundtransfer.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.training.fundtransfer.exception.GlobalErrorCode;
import org.training.fundtransfer.exception.ResourceNotFound;
import org.training.fundtransfer.external.AccountService;
import org.training.fundtransfer.external.UserService;
import org.training.fundtransfer.model.dto.Account;
import org.training.fundtransfer.model.dto.FundTransferDto;
import org.training.fundtransfer.model.dto.UserDto;
import org.training.fundtransfer.model.dto.request.FundTransferRequest;
import org.training.fundtransfer.model.dto.response.FundTransferResponse;
import org.training.fundtransfer.service.FundTransferService;

import java.util.List;
import java.util.Objects;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/fund-transfers")
public class FundTransferController {

    private final FundTransferService fundTransferService;
    private final AccountService accountService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<FundTransferResponse> fundTransfer(@RequestBody FundTransferRequest fundTransferRequest) {
        // Horizontal authorization: verify the requester owns the fromAccount
        String currentAuthId = SecurityContextHolder.getContext().getAuthentication().getName();
        ResponseEntity<Account> fromAccountResp = accountService.readByAccountNumber(fundTransferRequest.getFromAccount());
        if (Objects.isNull(fromAccountResp.getBody())) {
            throw new ResourceNotFound("From account not found", GlobalErrorCode.NOT_FOUND);
        }
        Account fromAccount = fromAccountResp.getBody();

        ResponseEntity<UserDto> userResp = userService.readUserById(fromAccount.getUserId());
        if (Objects.isNull(userResp.getBody()) || !currentAuthId.equals(userResp.getBody().getAuthId())) {
            throw new AccessDeniedException("Access denied: you do not own the source account");
        }

        return new ResponseEntity<>(fundTransferService.fundTransfer(fundTransferRequest), HttpStatus.CREATED);
    }

    @GetMapping("/{referenceId}")
    public ResponseEntity<FundTransferDto> getTransferDetailsFromReferenceId(@PathVariable String referenceId) {
        return new ResponseEntity<>(fundTransferService.getTransferDetailsFromReferenceId(referenceId), HttpStatus.OK);
    }

    @GetMapping
    public ResponseEntity<List<FundTransferDto>> getAllTransfersByAccountId(@RequestParam String accountId) {
        // Horizontal authorization: verify the requester owns this account
        String currentAuthId = SecurityContextHolder.getContext().getAuthentication().getName();
        ResponseEntity<Account> accountResp = accountService.readByAccountNumber(accountId);
        if (Objects.isNull(accountResp.getBody())) {
            throw new ResourceNotFound("Account not found", GlobalErrorCode.NOT_FOUND);
        }
        ResponseEntity<UserDto> userResp = userService.readUserById(accountResp.getBody().getUserId());
        if (Objects.isNull(userResp.getBody()) || !currentAuthId.equals(userResp.getBody().getAuthId())) {
            throw new AccessDeniedException("Access denied: you do not own this account");
        }

        return new ResponseEntity<>(fundTransferService.getAllTransfersByAccountId(accountId), HttpStatus.OK);
    }
}
