package com.fraud.bank.controller;

import com.fraud.bank.dto.AccountResponse;
import com.fraud.bank.dto.BalanceAdjustmentRequest;
import com.fraud.bank.dto.BankTransactionResponse;
import com.fraud.bank.service.BankService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/bank")
@RequiredArgsConstructor
public class AdminBankController {

    private final BankService bankService;

    @GetMapping("/accounts")
    public ResponseEntity<?> getAllAccounts(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) {
            return forbiddenResponse();
        }

        List<AccountResponse> accounts = bankService.getAllAccounts();
        log.info("[ADMIN-BANK-CTRL] Admin fetched {} bank accounts", accounts.size());
        return ResponseEntity.ok(accounts);
    }

    @PutMapping("/accounts/{accountNumber}/status")
    public ResponseEntity<?> updateAccountStatus(
            @PathVariable String accountNumber,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) {
            return forbiddenResponse();
        }

        String status = body != null ? body.get("status") : null;
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Status field is required (ACTIVE/FROZEN)"));
        }

        AccountResponse updated = bankService.updateAccountStatus(accountNumber, status);
        log.info("[ADMIN-BANK-CTRL] Account {} status set to {}", accountNumber, status);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/adjust-balance")
    public ResponseEntity<?> adjustBalance(
            @Valid @RequestBody BalanceAdjustmentRequest request,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) {
            return forbiddenResponse();
        }

        AccountResponse response = bankService.adjustBalance(
                request.getAccountNumber(),
                request.getAmount(),
                request.getType(),
                request.getReason()
        );
        log.info("[ADMIN-BANK-CTRL] Admin adjusted balance for account {}: {}", request.getAccountNumber(), request.getAmount());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions")
    public ResponseEntity<?> getAllBankTransactions(
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        if (!isAdmin(userEmail, userRole)) {
            return forbiddenResponse();
        }

        List<BankTransactionResponse> txs = bankService.getAllBankTransactions();
        return ResponseEntity.ok(txs);
    }

    private boolean isAdmin(String email, String role) {
        return "karthik@gmail.com".equalsIgnoreCase(email) && "ROLE_ADMIN".equals(role);
    }

    private ResponseEntity<?> forbiddenResponse() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "error", "Forbidden",
                        "message", "Access restricted: Only karthik@gmail.com has administrative privileges."
                ));
    }
}
