import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Transactions from '../../pages/Transactions';
import { getTransactionHistory } from '../../api/bankApi';
import { getPendingReviewTransactions, submitReviewDecision } from '../../api/fraudApi';

jest.mock('../../api/bankApi', () => ({
  getTransactionHistory: jest.fn(),
}));

jest.mock('../../api/fraudApi', () => ({
  getPendingReviewTransactions: jest.fn(),
  submitReviewDecision: jest.fn(),
}));

jest.mock('../../api/transactionApi', () => ({
  submitTransaction: jest.fn(),
  getTransactionStatus: jest.fn(),
  pollTransactionStatus: jest.fn(),
}));

jest.mock('../../context/AppStateContext', () => ({
  useAppState: () => ({ refreshBalance: jest.fn() }),
}));

jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ user: { email: 'payer@example.com' } }),
}));

describe('Transactions account activity', () => {
  const renderTransactions = () => render(
    <MemoryRouter>
      <Transactions />
    </MemoryRouter>,
  );

  beforeEach(() => {
    jest.clearAllMocks();
    getTransactionHistory.mockResolvedValue([
      {
        transactionId: 'payment-sent-123456789',
        type: 'DEBIT',
        amount: 500,
        balanceAfter: 9500,
        appliedAt: '2026-10-04T01:19:00',
      },
      {
        transactionId: 'payment-received-123456',
        type: 'CREDIT',
        amount: 700,
        balanceAfter: 10200,
        appliedAt: '2026-10-04T01:20:00',
      },
    ]);
    getPendingReviewTransactions.mockResolvedValue([]);
  });

  test('shows sent and received ledger entries with transaction IDs', async () => {
    renderTransactions();

    expect(await screen.findByText('Sent')).toBeInTheDocument();
    expect(screen.getByText('Received')).toBeInTheDocument();
    expect(screen.getByLabelText('Copy transaction ID payment-sent-123456789')).toBeInTheDocument();
    expect(screen.getByLabelText('Copy transaction ID payment-received-123456')).toBeInTheDocument();
    expect(getTransactionHistory).toHaveBeenCalledTimes(1);
  });

  test('shows accept and reject actions for a pending REVIEW payment', async () => {
    getPendingReviewTransactions.mockResolvedValue([{
      transactionId: 'review-123',
      amount: 500,
      payeeUpiId: 'receiver@upi',
      paymentMode: 'BANK',
      confidenceScore: 0.47,
      detectionLayer: 'AI',
      reason: 'Device changed between payments',
    }]);

    renderTransactions();

    expect(await screen.findByText('Transactions Awaiting Your Decision')).toBeInTheDocument();
    expect(await screen.findByText('receiver@upi')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Accept' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
  });

  test('accepts a reviewed payment only after confirmation', async () => {
    getPendingReviewTransactions.mockResolvedValue([{
      transactionId: 'review-123',
      amount: 500,
      payeeUpiId: 'receiver@upi',
      paymentMode: 'BANK',
      confidenceScore: 0.47,
      reason: 'Device changed between payments',
    }]);
    submitReviewDecision.mockResolvedValue({
      transactionId: 'review-123',
      decision: 'APPROVE',
      paymentStatus: 'SUCCESS',
    });

    renderTransactions();
    fireEvent.click(await screen.findByRole('button', { name: 'Accept' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Accept & send' }));

    await waitFor(() => {
      expect(submitReviewDecision).toHaveBeenCalledWith('review-123', 'APPROVE');
    });
  });
});
