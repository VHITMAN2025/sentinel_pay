import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import Dashboard from '../../pages/Dashboard';
import { fetchFraudResultWithRetry, getReviewDecision, submitReviewDecision } from '../../api/fraudApi';
import { sendUpiPayment, pollTransactionStatus } from '../../api/transactionApi';

jest.mock('../../api/fraudApi', () => ({
  fetchFraudResultWithRetry: jest.fn(),
  getReviewDecision: jest.fn(),
  submitReviewDecision: jest.fn(),
}));

jest.mock('../../api/transactionApi', () => ({
  sendUpiPayment: jest.fn(),
  pollTransactionStatus: jest.fn(),
}));

jest.mock('../../api/bankApi', () => ({
  getAccountByUpiId: jest.fn().mockResolvedValue({ userId: 'receiver@example.com' }),
}));

jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ user: { name: 'Payer' } }),
}));

const mockRecordTransaction = jest.fn();
jest.mock('../../context/AppStateContext', () => ({
  useAppState: () => ({
    stats: { safe: 0, blocked: 0, review: 0, total: 0 },
    recordTransaction: mockRecordTransaction,
    refreshBalance: jest.fn(),
  }),
}));

jest.mock('../../components/dashboard/BalanceCard', () => () => null);
jest.mock('../../components/dashboard/StatsCard', () => () => null);
jest.mock('../../components/dashboard/TransactionChart', () => () => null);
jest.mock('../../components/dashboard/ActivityFeed', () => () => null);
jest.mock('../../components/fraud/RiskGauge', () => () => null);
jest.mock('../../components/fraud/FraudExplanation', () => () => null);
jest.mock('../../components/fraud/TransactionTimeline', () => () => null);

describe('Dashboard review payment approval', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sendUpiPayment.mockResolvedValue({ transactionId: 'review-1' });
    pollTransactionStatus
      .mockResolvedValueOnce({
        transactionId: 'review-1',
        fraudStatus: 'REVIEW',
        overallStatus: 'REVIEW',
        amount: 1,
        payeeUpiId: 'receiver@upi',
      })
      .mockResolvedValueOnce({
        transactionId: 'review-1',
        fraudStatus: 'REVIEW',
        overallStatus: 'COMPLETE',
        paymentStatus: 'SUCCESS',
        amount: 1,
        payeeUpiId: 'receiver@upi',
      });
    fetchFraudResultWithRetry.mockResolvedValue({
      transactionId: 'review-1',
      userId: 'payer@example.com',
      status: 'REVIEW',
      confidenceScore: 0.55,
      reason: 'Uncertain signal',
    });
    getReviewDecision.mockResolvedValue({
      transactionId: 'review-1',
      decision: null,
      amount: 1,
      payeeUpiId: 'receiver@upi',
    });
    submitReviewDecision.mockResolvedValue({
      transactionId: 'review-1',
      decision: 'APPROVE',
      paymentStatus: 'SUCCESS',
    });
  });

  test('allows the payer to approve a one-rupee REVIEW payment from the payment result', async () => {
    render(<Dashboard />);

    fireEvent.change(screen.getByLabelText('Payee UPI ID'), {
      target: { value: 'receiver@upi' },
    });
    fireEvent.change(screen.getByLabelText('Amount (₹)'), {
      target: { value: '1' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send Payment' }));

    expect(await screen.findByText('This payment is waiting for your decision')).toBeInTheDocument();
    expect(sendUpiPayment).toHaveBeenCalledWith(expect.objectContaining({
      location: expect.any(String),
      device: expect.any(String),
      merchantCategory: 'Food',
    }));
    fireEvent.click(screen.getByRole('button', { name: /approve & send/i }));
    expect(await screen.findByText(/approve and send this payment/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /confirm & send/i }));

    await waitFor(() => {
      expect(submitReviewDecision).toHaveBeenCalledWith('review-1', 'APPROVE');
    });
    expect(await screen.findByText(/approved — payment completed and recipient credited/i)).toBeInTheDocument();
    expect(mockRecordTransaction).toHaveBeenCalledWith(expect.objectContaining({
      transactionId: 'review-1',
      fraudStatus: 'REVIEW',
      overallStatus: 'COMPLETE',
      paymentStatus: 'SUCCESS',
    }));
  });
});
