package com.fraud.bank.controller;

import com.fraud.bank.service.BankService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankControllerTest {

    @Mock
    private BankService bankService;

    @InjectMocks
    private BankController bankController;

    @Test
    void requiresGatewayIdentityForLedgerHistory() {
        var response = bankController.getTransactionHistory(null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(bankService, never()).getTransactionHistory(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void scopesLedgerHistoryToGatewayIdentity() {
        when(bankService.getTransactionHistory("payer@example.com")).thenReturn(List.of());

        var response = bankController.getTransactionHistory("payer@example.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(bankService).getTransactionHistory("payer@example.com");
    }
}
