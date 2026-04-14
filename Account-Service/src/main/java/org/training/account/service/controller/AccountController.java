package org.training.account.service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.training.account.service.external.UserService;
import org.training.account.service.model.dto.AccountDto;
import org.training.account.service.model.dto.AccountStatusUpdate;
import org.training.account.service.model.dto.response.Response;
import org.training.account.service.model.dto.external.TransactionResponse;
import org.training.account.service.service.AccountService;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final UserService userService;

    /**
     * Create an account using the provided accountDto
     *
     * @param accountDto The account details
     * @return The response entity with the created account and HTTP status code
     */
    @PostMapping
    public ResponseEntity<Response> createAccount(@RequestBody AccountDto accountDto) {
        return new ResponseEntity<>(accountService.createAccount(accountDto), HttpStatus.CREATED);
    }

    /**
     * Update the status of an account.
     *
     * @param accountNumber       The account number of the account to update.
     * @param accountStatusUpdate The account status update containing the new status.
     * @return The response entity with the updated account status.
     */
    @PatchMapping
    public ResponseEntity<Response> updateAccountStatus(@RequestParam String accountNumber,@RequestBody AccountStatusUpdate accountStatusUpdate) {
        return ResponseEntity.ok(accountService.updateStatus(accountNumber, accountStatusUpdate));
    }

//    /**
//     * Retrieves an account by its account number.
//     *
//     * @param accountNumber The account number to search for.
//     * @return The account DTO if found, or a 404 response if not found.
//     */
//    @GetMapping
//    public ResponseEntity<AccountDto> readByAccountNumber(@RequestParam String accountNumber) {
//        return ResponseEntity.ok(accountService.readAccountByAccountNumber(accountNumber));
//    }


// ... 其他 import

    @GetMapping
    public ResponseEntity<AccountDto> readByAccountNumber(@RequestParam String accountNumber) {


        // 这里简单改动了一下
        // 本质上，这里应该是 塞到service里面去做
        // 原本存在的问题：
        // 之前登录校验 采用 网关解析token + user信息透传给 后端其他服务
        // 导致一个问题，内网陷落： 有可能直接从内网访问 后面的某个 服务，直接url上跟上用户参数就可以请求接口，绕过了网关
        // 其次还有一个问题：
        // 水平越权：
        // 比如这里zhangsan用户完全可以查询 lisi的信息，因为这里服务原本 完全信任地址栏透传过来的 用户信息

        // 改动：
        // 改为 网关初步解析token/或者登录获取token  +  后端服务零信任 再次校验token
        // 然后这里 每个请求里面，还要去校验token 对应的用户 是否有这个权限。







        // ================== 第一重防线：提取真实身份 ==================
        // 就像保安用仪器扫描了通行证，系统从 SecurityContext 中强制提取刚才验签通过的 JWT Token 信息
        // 这个 getName() 默认拿到的就是 Keycloak 颁发的唯一标识（对应咱们 user 表里的 auth_id）

        log.info("accountNumber={}", accountNumber);

        String currentAuthId = SecurityContextHolder.getContext().getAuthentication().getName();

        // ================== 第二重防线：获取数据主体 ==================
        // 先把前端想要查的账户信息从数据库里拿出来
        AccountDto targetAccount = accountService.readAccountByAccountNumber(accountNumber);

        // ================== 第三重防线：核对数据权属 ==================
        // 核心逻辑：核对这个 TargetAccount 到底是不是 currentAuthId 的？
        // (注：这里需要你根据实际代码稍作调整。假设你能通过 targetAccount.getUserId() 查到该账户主人的 auth_id)

        String ownerAuthId = userService.readUserById(targetAccount.getUserId()).getBody().getAuthId(); // 需根据你的业务类实现

        if (!currentAuthId.equals(ownerAuthId)) {
            // 如果通行证上的人，和账户的主人不是同一个人，直接触发警报！
            throw new AccessDeniedException("越权访问警告！您无权查看他人的账户数据。您的非法操作已被系统记录！");
        }

        // 只有经过了上面严密的核对，才允许把数据返回给前端
        return ResponseEntity.ok(targetAccount);
    }

    /**
     * Updates an account with the given account number.
     *
     * @param accountNumber The account number of the account to be updated.
     * @param accountDto The updated account information.
     * @return The response entity with the updated account information.
     */
    @PutMapping
    public ResponseEntity<Response> updateAccount(@RequestParam String accountNumber, @RequestBody AccountDto accountDto) {
        return ResponseEntity.ok(accountService.updateAccount(accountNumber, accountDto));
    }

    /**
     * Retrieves the balance of the specified account.
     *
     * @param accountNumber The account number
     * @return The account balance
     */
    @GetMapping("/balance")
    public ResponseEntity<String> accountBalance(@RequestParam String accountNumber) {
        return ResponseEntity.ok(accountService.getBalance(accountNumber));
    }

    /**
     * Retrieve the list of transactions for a given account ID.
     *
     * @param accountId The ID of the account.
     * @return A ResponseEntity object containing a list of TransactionResponse objects.
     */
    @GetMapping("/{accountId}/transactions")
    public ResponseEntity<List<TransactionResponse>> getTransactionsFromAccountId(@PathVariable String accountId) {
        return ResponseEntity.ok(accountService.getTransactionsFromAccountId(accountId));
    }

    /**
     * Closes an account.
     *
     * @param accountNumber The account number of the account to be closed.
     * @return The response entity with the result of closing the account.
     */
    @PutMapping("/closure")
    public ResponseEntity<Response> closeAccount(@RequestParam String accountNumber) {
        return ResponseEntity.ok(accountService.closeAccount(accountNumber));
    }

    /**
     * Retrieves the account for a given user ID.
     *
     * @param userId the ID of the user
     * @return the account of the user
     */
    @GetMapping("/{userId}")
    public ResponseEntity<AccountDto> readAccountByUserId(@PathVariable Long userId){
        return ResponseEntity.ok(accountService.readAccountByUserId(userId));











    }
}
