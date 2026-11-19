package com.fraud.bank.service;

import com.fraud.bank.dto.BankTransactionResponse;
import com.fraud.bank.entity.BankTransaction;
import com.fraud.bank.entity.TransactionType;
import com.fraud.bank.repository.BankAccountRepository;
import com.fraud.bank.repository.BankTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankServiceTest {

    @Mock
    private BankAccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @InjectMocks
    private BankService bankService;

    @Test
    void transactionHistoryReturnsOnlyTheRequestedUsersLedgerEntries() {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 10, 4, 1, 19);
        BankTransaction debit = BankTransaction.builder()
                .transactionId("payment-123")
                .type(TransactionType.DEBIT)
                .userId("payer@example.com")
                .amount(new BigDecimal("500.00"))
                .balanceAfter(new BigDecimal("9500.00"))
                .appliedAt(appliedAt)
                .build();
        when(bankTransactionRepository.findTop100ByUserIdOrderByAppliedAtDesc("payer@example.com"))
                .thenReturn(List.of(debit));

        List<BankTransactionResponse> history = bankService.getTransactionHistory("payer@example.com");

        assertEquals(1, history.size());
        assertEquals("payment-123", history.get(0).getTransactionId());
        assertEquals(TransactionType.DEBIT, history.get(0).getType());
        assertEquals(new BigDecimal("500.00"), history.get(0).getAmount());
        assertEquals(new BigDecimal("9500.00"), history.get(0).getBalanceAfter());
        assertEquals(appliedAt, history.get(0).getAppliedAt());
        verify(bankTransactionRepository).findTop100ByUserIdOrderByAppliedAtDesc("payer@example.com");
    }
}
