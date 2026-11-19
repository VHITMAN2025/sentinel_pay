import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import FraudAlerts from '../../pages/FraudAlerts';
import { getFraudResult, getReviewDecision, submitReviewDecision } from '../../api/fraudApi';

jest.mock('../../api/fraudApi', () => ({
  getFraudResult: jest.fn(),
  getUserFraudHistory: jest.fn(),
  getReviewDecision: jest.fn(),
  submitReviewDecision: jest.fn(),
}));

jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ user: { email: 'payer@example.com' } }),
}));

jest.mock('../../context/AppStateContext', () => ({
  useAppState: () => ({
    stats: { safe: 0, blocked: 0, review: 0, total: 0 },
    recordTransaction: jest.fn(),
    refreshBalance: jest.fn(),
  }),
}));

jest.mock('../../components/fraud/RiskGauge', () => () => null);
jest.mock('../../components/fraud/FraudTrendChart', () => () => null);
jest.mock('../../components/fraud/AlertsPanel', () => () => null);

describe('FraudAlerts review decisions', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getFraudResult.mockResolvedValue({
      status: 200,
      data: {
        transactionId: 'review-123',
        userId: 'payer@example.com',
        status: 'REVIEW',
        reason: 'First transaction requires review',
        confidenceScore: 0.5,
      },
    });
    getReviewDecision.mockResolvedValue({
      transactionId: 'review-123',
      amount: 500,
      payeeUpiId: 'receiver@upi',
      decision: null,
    });
    submitReviewDecision.mockResolvedValue({
      transactionId: 'review-123',
      userId: 'payer@example.com',
      decision: 'APPROVE',
      paymentStatus: 'SUCCESS',
      amount: 500,
      payeeUpiId: 'receiver@upi',
    });
  });

  test('requires explicit approval before sending a reviewed payment', async () => {
    render(<FraudAlerts />);

    fireEvent.change(screen.getByPlaceholderText('TXN-001'), {
      target: { value: 'review-123' },
    });
    fireEvent.submit(screen.getByPlaceholderText('TXN-001').closest('form'));

    expect(await screen.findByText('Your decision')).toBeInTheDocument();
    expect(screen.getByText(/overrides the automated REVIEW hold/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /approve & send/i }));
    expect(await screen.findByText(/approve this reviewed payment/i)).toBeInTheDocument();
    expect(screen.getByText(/₹500\.00/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /confirm & send/i }));

    await waitFor(() => {
      expect(submitReviewDecision).toHaveBeenCalledWith('review-123', 'APPROVE');
    });
    expect(await screen.findByText(/approved — payment completed and recipient credited/i)).toBeInTheDocument();
  });
});
