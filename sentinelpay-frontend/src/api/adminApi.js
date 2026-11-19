/**
 * adminApi.js — Centralised API client for SentinelPay Admin Portal
 *
 * All endpoints are routed through the API Gateway with JWT Bearer tokens
 * and strictly validated for karthik@gmail.com and ROLE_ADMIN.
 */

import apiClient from './axiosConfig';

export async function getAdminStats() {
  const { data } = await apiClient.get('/api/admin/fraud/stats');
  return data;
}

export async function getSystemHealth() {
  const { data } = await apiClient.get('/api/admin/fraud/system/health');
  return data;
}

export async function getAdminUsers() {
  const { data } = await apiClient.get('/api/admin/users');
  return data;
}

export async function getAdminBankAccounts() {
  const { data } = await apiClient.get('/api/admin/bank/accounts');
  return data;
}

export async function updateBankAccountStatus(accountNumber, status) {
  const { data } = await apiClient.put(`/api/admin/bank/accounts/${accountNumber}/status`, { status });
  return data;
}

export async function adjustBankAccountBalance(payload) {
  const { data } = await apiClient.post('/api/admin/bank/adjust-balance', payload);
  return data;
}

export async function getAdminBankTransactions() {
  const { data } = await apiClient.get('/api/admin/bank/transactions');
  return data;
}

export async function getAdminFraudTransactions() {
  const { data } = await apiClient.get('/api/admin/fraud/transactions');
  return data;
}

export async function getAdminReviewQueue() {
  const { data } = await apiClient.get('/api/admin/fraud/review-queue');
  return data;
}

export async function submitAdminReview(transactionId, decision) {
  const { data } = await apiClient.post(`/api/admin/fraud/review/${transactionId}`, { decision });
  return data;
}

export async function getAdminFraudRules() {
  const { data } = await apiClient.get('/api/admin/fraud/rules');
  return data;
}

export async function updateAdminFraudRules(rules) {
  const { data } = await apiClient.put('/api/admin/fraud/rules', rules);
  return data;
}

export async function getAdminBlocklist() {
  const { data } = await apiClient.get('/api/admin/fraud/blocklist');
  return data;
}

export async function addToBlocklist(payload) {
  const { data } = await apiClient.post('/api/admin/fraud/blocklist', payload);
  return data;
}

export async function removeFromBlocklist(item) {
  const { data } = await apiClient.delete(`/api/admin/fraud/blocklist/${encodeURIComponent(item)}`);
  return data;
}

export async function getAdminAuditLogs() {
  const { data } = await apiClient.get('/api/admin/fraud/audit-logs');
  return data;
}
