package com.fraud.bank.service;

import com.fraud.bank.dto.AccountResponse;
import com.fraud.bank.dto.BalanceResponse;
import com.fraud.bank.dto.BankTransactionResponse;
import com.fraud.bank.dto.CreateAccountRequest;
import com.fraud.bank.dto.DebitCreditRequest;
import com.fraud.bank.dto.PinCheckResult;
import com.fraud.bank.entity.BankAccount;
import com.fraud.bank.entity.BankTransaction;
import com.fraud.bank.entity.TransactionType;
import com.fraud.bank.exception.AccountNotFoundException;
import com.fraud.bank.exception.InsufficientFundsException;
import com.fraud.bank.repository.BankAccountRepository;
import com.fraud.bank.repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BankService — core banking operations (Polish v3)
 *
 * CRITICAL INVARIANT:
 *   Debit and Credit are ONLY called by fraud-detection-service AFTER a SAFE verdict.
 *   This service does not check fraud — it trusts the caller.
 *   In production, add HMAC or mutual TLS between services.
 *
 * Concurrency safety:
 *   - Both debit() and credit() use pessimistic write lock (SELECT ... FOR UPDATE)
 *     to prevent concurrent balance corruption.
 *   - BankAccount.@Version provides a second layer via optimistic locking.
 *
 * Idempotency:
 *   - BankTransaction records (unique on transactionId + type) ensure debit and credit
 *     are applied exactly once, even on Kafka retry storms.
 *   - Duplicate debit/credit for the same txId → returns 200 with current balance,
 *     no double-debit or double-credit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankService {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("10000.00");

    private final BankAccountRepository    accountRepository;
    private final BankTransactionRepository bankTransactionRepository;

    // ─── Create Account ───────────────────────────────────────────────────────

    /**
     * Create a new bank account for a newly registered user.
     * Called by auth-service immediately after user registration.
     * Seeds the account with a default balance of 10,000 INR.
     *
     * @throws IllegalArgumentException if userId or upiId already has an account
     */
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        if (accountRepository.existsByUserId(request.getUserId())) {
            throw new IllegalArgumentException(
                "Bank account already exists for userId: " + request.getUserId());
        }
        if (accountRepository.existsByUpiId(request.getUpiId())) {
            throw new IllegalArgumentException(
                "Bank account already exists for upiId: " + request.getUpiId());
        }

        BankAccount account = BankAccount.builder()
                .userId(request.getUserId())
                .upiId(request.getUpiId())
                .balance(DEFAULT_BALANCE)
                .build();

        account = accountRepository.save(account);
        log.info("[BANK] Account created: userId={} upiId={} accountNumber={} balance={}",
                 account.getUserId(), account.getUpiId(),
                 account.getAccountNumber(), account.getBalance());

        return toAccountResponse(account, "Account created successfully");
    }

    // ─── Debit ────────────────────────────────────────────────────────────────

    /**
     * Debit (subtract) the given amount from the account.
     *
     * Idempotency: if a DEBIT record already exists for this transactionId, skips
     * the debit and returns the current balance — safe on Kafka retry.
     *
     * Uses a pessimistic write lock to prevent concurrent overdrafts.
     * Called ONLY by fraud-detection-service after a SAFE verdict.
     *
     * @throws AccountNotFoundException   if no account found for userId
     * @throws InsufficientFundsException if balance < amount
     */
    @Transactional
    public AccountResponse debit(DebitCreditRequest request) {
        // Idempotency gate — reject double-debit for same transaction
        if (bankTransactionRepository.existsByTransactionIdAndType(
                request.getTransactionId(), TransactionType.DEBIT)) {
            log.warn("[BANK] Idempotency: DEBIT already applied — txId={} userId={}. Skipping.",
                    request.getTransactionId(), request.getUserId());
            BankAccount account = accountRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new AccountNotFoundException(
                        "No bank account found for userId: " + request.getUserId()));
            return toAccountResponse(account, "Debit already applied (idempotent)");
        }

        // Pessimistic write lock — prevents concurrent overdraft
        BankAccount account = accountRepository
                .findByUserIdForUpdate(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + request.getUserId()));

        if ("FROZEN".equalsIgnoreCase(account.getAccountStatus())) {
            log.warn("[BANK] Debit rejected: Account is FROZEN by administrator: userId={}", request.getUserId());
            throw new IllegalStateException("Bank account is FROZEN by administrator. Debits are blocked.");
        }

        if (account.getBalance().compareTo(request.getAmount()) < 0) {
            log.warn("[BANK] Insufficient funds: userId={} balance={} requested={} txId={}",
                     request.getUserId(), account.getBalance(),
                     request.getAmount(), request.getTransactionId());
            throw new InsufficientFundsException(
                String.format("Insufficient funds. Available: %.2f, Requested: %.2f",
                              account.getBalance(), request.getAmount()));
        }

        BigDecimal newBalance = account.getBalance().subtract(request.getAmount());
        account.setBalance(newBalance);
        accountRepository.save(account);

        // Record the transaction for idempotency
        bankTransactionRepository.save(BankTransaction.builder()
                .transactionId(request.getTransactionId())
                .type(TransactionType.DEBIT)
                .userId(request.getUserId())
                .amount(request.getAmount())
                .balanceAfter(newBalance)
                .appliedAt(LocalDateTime.now())
                .build());

        log.info("[BANK_TX] DEBIT txId={} userId={} amount={} newBalance={}",
                 request.getTransactionId(), request.getUserId(),
                 request.getAmount(), newBalance);

        return toAccountResponse(account, "Debit successful");
    }

    // ─── Credit ───────────────────────────────────────────────────────────────

    /**
     * Credit (add) the given amount to the account.
     *
     * Idempotency: if a CREDIT record already exists for this transactionId, skips
     * the credit and returns the current balance — safe on Kafka retry.
     *
     * Uses a pessimistic write lock (same as debit) to prevent concurrent
     * balance corruption when multiple credits race for the same account.
     * Called ONLY by fraud-detection-service after a SAFE verdict.
     *
     * @throws AccountNotFoundException if no account found for userId
     */
    @Transactional
    public AccountResponse credit(DebitCreditRequest request) {
        // Idempotency gate — reject double-credit for same transaction
        if (bankTransactionRepository.existsByTransactionIdAndType(
                request.getTransactionId(), TransactionType.CREDIT)) {
            log.warn("[BANK] Idempotency: CREDIT already applied — txId={} userId={}. Skipping.",
                    request.getTransactionId(), request.getUserId());
            BankAccount account = accountRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new AccountNotFoundException(
                        "No bank account found for userId: " + request.getUserId()));
            return toAccountResponse(account, "Credit already applied (idempotent)");
        }

        // Pessimistic write lock — prevents concurrent balance corruption
        BankAccount account = accountRepository
                .findByUserIdForUpdate(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + request.getUserId()));

        if ("FROZEN".equalsIgnoreCase(account.getAccountStatus())) {
            log.warn("[BANK] Credit rejected: Account is FROZEN by administrator: userId={}", request.getUserId());
            throw new IllegalStateException("Bank account is FROZEN by administrator. Credits are blocked.");
        }

        BigDecimal newBalance = account.getBalance().add(request.getAmount());
        account.setBalance(newBalance);
        accountRepository.save(account);

        // Record the transaction for idempotency
        bankTransactionRepository.save(BankTransaction.builder()
                .transactionId(request.getTransactionId())
                .type(TransactionType.CREDIT)
                .userId(request.getUserId())
                .amount(request.getAmount())
                .balanceAfter(newBalance)
                .appliedAt(LocalDateTime.now())
                .build());

        log.info("[BANK_TX] CREDIT txId={} userId={} amount={} newBalance={}",
                 request.getTransactionId(), request.getUserId(),
                 request.getAmount(), newBalance);

        return toAccountResponse(account, "Credit successful");
    }

    // ─── Refund ───────────────────────────────────────────────────────────────

    /**
     * Refund — credits the payer back after a failed credit-to-payee.
     * Uses a separate TransactionType (REFUND) so idempotency is independent
     * of the CREDIT operation.
     *
     * Called by the saga compensation flow in fraud-detection-service.
     *
     * @throws AccountNotFoundException if no account found for userId
     */
    @Transactional
    public AccountResponse refund(DebitCreditRequest request) {
        // Idempotency gate
        if (bankTransactionRepository.existsByTransactionIdAndType(
                request.getTransactionId(), TransactionType.REFUND)) {
            log.warn("[BANK] Idempotency: REFUND already applied — txId={} userId={}. Skipping.",
                    request.getTransactionId(), request.getUserId());
            BankAccount account = accountRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new AccountNotFoundException(
                        "No bank account found for userId: " + request.getUserId()));
            return toAccountResponse(account, "Refund already applied (idempotent)");
        }

        BankAccount account = accountRepository
                .findByUserIdForUpdate(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + request.getUserId()));

        BigDecimal newBalance = account.getBalance().add(request.getAmount());
        account.setBalance(newBalance);
        accountRepository.save(account);

        bankTransactionRepository.save(BankTransaction.builder()
                .transactionId(request.getTransactionId())
                .type(TransactionType.REFUND)
                .userId(request.getUserId())
                .amount(request.getAmount())
                .balanceAfter(newBalance)
                .appliedAt(LocalDateTime.now())
                .build());

        log.info("[BANK_TX] REFUND txId={} userId={} amount={} newBalance={}",
                 request.getTransactionId(), request.getUserId(),
                 request.getAmount(), newBalance);

        return toAccountResponse(account, "Refund successful");
    }

    // ─── Balance ──────────────────────────────────────────────────────────────

    /**
     * Fetch current balance for a user.
     * Called by authenticated users via the API gateway (GET /bank/balance).
     * userId is always sourced from the X-User-Email header (set by gateway
     * from JWT subject) — never from a request parameter.
     *
     * @throws AccountNotFoundException if no account found for userId
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String userId) {
        return getBalance(userId, null);
    }

    /**
     * Fetch current balance for a user with optional PIN verification.
     * If upiPin is provided, validates that it matches.
     */
    @Transactional
    public BalanceResponse getBalance(String userId, String upiPin) {
        BankAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + userId));

        if (upiPin != null && !upiPin.isBlank()) {
            PinCheckResult pinCheck = verifyUpiPinWithResult(userId, upiPin);
            if (!pinCheck.isValid()) {
                log.warn("[BANK-BALANCE] Access denied for userId={}: {}", userId, pinCheck.getMessage());
                throw new SecurityException(pinCheck.getMessage());
            }
        }

        return BalanceResponse.builder()
                .userId(account.getUserId())
                .upiId(account.getUpiId())
                .balance(account.getBalance())
                .currency(DEFAULT_CURRENCY)
                .accountStatus(account.getAccountStatus() != null ? account.getAccountStatus() : "ACTIVE")
                .build();
    }

    // ─── UPI PIN Operations ──────────────────────────────────────────────────

    /**
     * Verify whether the supplied UPI PIN matches the account's PIN.
     * Enforces 3-attempt lockout with a 6-hour temporary security hold.
     */
    @Transactional
    public PinCheckResult verifyUpiPinWithResult(String userId, String rawPin) {
        if (userId == null || rawPin == null || rawPin.isBlank()) {
            return PinCheckResult.builder()
                    .valid(false)
                    .locked(false)
                    .remainingAttempts(0)
                    .message("User ID and UPI PIN are required")
                    .build();
        }
        BankAccount account = accountRepository.findByUserId(userId).orElse(null);
        if (account == null) {
            log.warn("[BANK-PIN] Account not found for PIN verification: userId={}", userId);
            return PinCheckResult.builder()
                    .valid(false)
                    .locked(false)
                    .remainingAttempts(0)
                    .message("Bank account not found for user")
                    .build();
        }

        // 1. Check if currently locked
        LocalDateTime now = LocalDateTime.now();
        if (account.getPinLockedUntil() != null && now.isBefore(account.getPinLockedUntil())) {
            long minutesRemaining = java.time.Duration.between(now, account.getPinLockedUntil()).toMinutes();
            long hoursRemaining = (minutesRemaining / 60) + (minutesRemaining % 60 > 0 ? 1 : 0);
            String lockMsg = String.format(
                    "UPI PIN is temporarily locked due to multiple failed attempts. Please try again after %d hours.",
                    Math.max(1, hoursRemaining));
            log.warn("[BANK-PIN] Access blocked: Account is PIN-locked until {} for userId={}",
                    account.getPinLockedUntil(), userId);
            return PinCheckResult.builder()
                    .valid(false)
                    .locked(true)
                    .remainingAttempts(0)
                    .lockedHoursRemaining(hoursRemaining)
                    .message(lockMsg)
                    .build();
        }

        // Lock expired — reset lock
        if (account.getPinLockedUntil() != null && !now.isBefore(account.getPinLockedUntil())) {
            account.setPinLockedUntil(null);
            account.setFailedPinAttempts(0);
        }

        String storedPin = account.getUpiPin();
        if (storedPin == null || storedPin.isBlank()) {
            storedPin = "212006";
        }

        boolean matches = storedPin.equals(rawPin.trim());
        if (!matches) {
            int newAttempts = account.getFailedPinAttempts() + 1;
            if (newAttempts >= 3) {
                // Lock for 6 hours!
                LocalDateTime lockUntil = now.plusHours(6);
                account.setFailedPinAttempts(0);
                account.setPinLockedUntil(lockUntil);
                accountRepository.save(account);
                log.warn("[BANK-PIN] 3 failed PIN attempts for userId={}. Locked until {}", userId, lockUntil);
                return PinCheckResult.builder()
                        .valid(false)
                        .locked(true)
                        .remainingAttempts(0)
                        .lockedHoursRemaining(6L)
                        .message("Incorrect UPI PIN. Maximum attempts exceeded. UPI PIN is temporarily locked for 6 hours. Please try again after 6 hours.")
                        .build();
            } else {
                int remaining = 3 - newAttempts;
                account.setFailedPinAttempts(newAttempts);
                accountRepository.save(account);
                log.warn("[BANK-PIN] Incorrect UPI PIN attempt ({}/3) for userId={}", newAttempts, userId);
                return PinCheckResult.builder()
                        .valid(false)
                        .locked(false)
                        .remainingAttempts(remaining)
                        .message(String.format("Incorrect UPI PIN. %d attempt%s remaining before a 6-hour security lock. Please try again.",
                                remaining, remaining > 1 ? "s" : ""))
                        .build();
            }
        }

        // Correct PIN
        if (account.getFailedPinAttempts() > 0 || account.getPinLockedUntil() != null) {
            account.setFailedPinAttempts(0);
            account.setPinLockedUntil(null);
            accountRepository.save(account);
        }

        log.info("[BANK-PIN] UPI PIN verified successfully for userId={}", userId);
        return PinCheckResult.builder()
                .valid(true)
                .locked(false)
                .remainingAttempts(3)
                .message("UPI PIN verified successfully.")
                .build();
    }

    /**
     * Verify whether the supplied UPI PIN matches the account's PIN.
     * Default PIN is "212006".
     */
    @Transactional
    public boolean verifyUpiPin(String userId, String rawPin) {
        return verifyUpiPinWithResult(userId, rawPin).isValid();
    }

    /**
     * Update the UPI PIN for an authenticated user.
     * Requires valid current PIN and a 6-digit numeric new PIN.
     */
    @Transactional
    public void changeUpiPin(String userId, String currentPin, String newPin) {
        BankAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException("No bank account found for userId: " + userId));

        String storedPin = account.getUpiPin();
        if (storedPin == null || storedPin.isBlank()) {
            storedPin = "212006";
        }

        if (!storedPin.equals(currentPin != null ? currentPin.trim() : "")) {
            throw new IllegalArgumentException("Current UPI PIN is incorrect.");
        }

        if (newPin == null || !newPin.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("New UPI PIN must be exactly 6 digits.");
        }

        account.setUpiPin(newPin.trim());
        account.setFailedPinAttempts(0);
        account.setPinLockedUntil(null);
        accountRepository.save(account);
        log.info("[BANK-PIN] UPI PIN changed successfully for userId={}", userId);
    }

    @Transactional(readOnly = true)
    public List<BankTransactionResponse> getTransactionHistory(String userId) {
        return bankTransactionRepository.findTop100ByUserIdOrderByAppliedAtDesc(userId)
                .stream()
                .map(transaction -> BankTransactionResponse.builder()
                        .transactionId(transaction.getTransactionId())
                        .type(transaction.getType())
                        .amount(transaction.getAmount())
                        .balanceAfter(transaction.getBalanceAfter())
                        .appliedAt(transaction.getAppliedAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ─── Lookup by UPI ID ─────────────────────────────────────────────────────

    /**
     * Look up an account by UPI ID — used by fraud-detection-service
     * and transaction-service to validate/resolve the payee before payment.
     * If payee is an external UPI ID not yet in the system, automatically
     * provisions a simulated external account so fraud detection scoring can execute.
     */
    @Transactional
    public AccountResponse findByUpiId(String upiId) {
        if (upiId == null || !upiId.contains("@")) {
            throw new AccountNotFoundException("Invalid UPI ID: " + upiId);
        }

        BankAccount account = accountRepository.findByUpiId(upiId.trim()).orElse(null);
        if (account == null) {
            // Auto-provision simulated external bank account for UPI ecosystem simulation
            String syntheticEmail = upiId.trim().replace("@", "_") + "@simulated-bank.in";
            account = BankAccount.builder()
                    .userId(syntheticEmail)
                    .upiId(upiId.trim())
                    .balance(DEFAULT_BALANCE)
                    .accountStatus("ACTIVE")
                    .build();
            account = accountRepository.save(account);
            log.info("[BANK] Auto-provisioned simulated external UPI account: upiId={} userId={}", upiId, syntheticEmail);
        }

        return toAccountResponse(account, null);
    }

    // ─── Admin Operations ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AccountResponse> getAllAccounts() {
        return accountRepository.findAll().stream()
                .map(acc -> toAccountResponse(acc, null))
                .collect(Collectors.toList());
    }

    @Transactional
    public AccountResponse updateAccountStatus(String accountNumber, String status) {
        BankAccount account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
        account.setAccountStatus(status.toUpperCase());
        accountRepository.save(account);
        log.info("[ADMIN-BANK] Account {} status updated to {}", accountNumber, status);
        return toAccountResponse(account, "Status updated to " + status);
    }

    @Transactional
    public AccountResponse adjustBalance(String accountNumber, BigDecimal amount, String type, String reason) {
        BankAccount account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));

        BigDecimal current = account.getBalance();
        BigDecimal updated;
        TransactionType txType;
        if ("DEBIT".equalsIgnoreCase(type)) {
            if (current.compareTo(amount) < 0) {
                throw new InsufficientFundsException("Cannot debit amount greater than balance: " + current);
            }
            updated = current.subtract(amount);
            txType = TransactionType.DEBIT;
        } else {
            updated = current.add(amount);
            txType = TransactionType.CREDIT;
        }

        account.setBalance(updated);
        accountRepository.save(account);

        String txId = "ADJ-" + UUID.randomUUID().toString().substring(0, 8);
        bankTransactionRepository.save(BankTransaction.builder()
                .transactionId(txId)
                .type(txType)
                .userId(account.getUserId())
                .amount(amount)
                .balanceAfter(updated)
                .appliedAt(LocalDateTime.now())
                .build());

        log.info("[ADMIN-BANK] Balance adjustment on {}: type={} amount={} newBalance={} reason={}",
                accountNumber, type, amount, updated, reason);

        return toAccountResponse(account, "Balance adjusted (" + type + " " + amount + ")");
    }

    @Transactional(readOnly = true)
    public List<BankTransactionResponse> getAllBankTransactions() {
        return bankTransactionRepository.findTop200ByOrderByAppliedAtDesc().stream()
                .map(tx -> BankTransactionResponse.builder()
                        .transactionId(tx.getTransactionId())
                        .type(tx.getType())
                        .amount(tx.getAmount())
                        .balanceAfter(tx.getBalanceAfter())
                        .appliedAt(tx.getAppliedAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private AccountResponse toAccountResponse(BankAccount account, String message) {
        return AccountResponse.builder()
                .userId(account.getUserId())
                .upiId(account.getUpiId())
                .accountNumber(account.getAccountNumber())
                .bankName(account.getBankName())
                .ifsc(account.getIfsc())
                .balance(account.getBalance())
                .accountStatus(account.getAccountStatus() != null ? account.getAccountStatus() : "ACTIVE")
                .createdAt(account.getCreatedAt())
                .message(message)
                .build();
    }
}
