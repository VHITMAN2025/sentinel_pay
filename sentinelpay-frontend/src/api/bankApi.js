import apiClient from './axiosConfig';

/**
 * bankApi — wraps the external bank-service endpoints
 *
 * Only the two EXTERNAL endpoints are exposed here.
 * /debit, /credit, /refund are INTERNAL service-to-service calls and
 * must never be called from the frontend.
 *
 * GET /bank/balance           → BalanceResponse (authenticated user's own balance)
 * GET /bank/account/by-upi/{upiId} → AccountResponse (payee lookup by UPI ID)
 */

/**
 * Get the authenticated user's bank balance.
 * Identity comes from the JWT (gateway injects X-User-Email).
 * Optionally verified with 6-digit UPI PIN.
 *
 * BalanceResponse: { userId, upiId, balance, currency, accountStatus }
 */
export const getBalance = async (upiPin = null) => {
  const config = upiPin ? { headers: { 'X-UPI-PIN': upiPin } } : {};
  const { data } = await apiClient.get('/bank/balance', config);
  return data;
};

/**
 * Verify the user's 6-digit UPI PIN.
 * @param {string} upiPin 
 */
export const verifyUpiPin = async (upiPin) => {
  const { data } = await apiClient.post('/bank/pin/verify', { upiPin });
  return data;
};

/**
 * Change the user's UPI PIN.
 * @param {string} currentPin 
 * @param {string} newPin 
 */
export const changeUpiPin = async (currentPin, newPin) => {
  const { data } = await apiClient.post('/bank/pin/change', { currentPin, newPin });
  return data;
};

/**
 * Get the authenticated user's latest persisted debit, credit, and refund entries.
 * Identity comes from the JWT (gateway injects X-User-Email).
 */
export const getTransactionHistory = async () => {
  const { data } = await apiClient.get('/bank/transactions');
  return data;
};

/**
 * Look up a bank account by UPI ID (payee resolution before sending money).
 * AccountResponse: { userId, upiId, name, accountStatus }
 */
export const getAccountByUpiId = async (upiId) => {
  const { data } = await apiClient.get(`/bank/account/by-upi/${encodeURIComponent(upiId)}`);
  return data;
};
