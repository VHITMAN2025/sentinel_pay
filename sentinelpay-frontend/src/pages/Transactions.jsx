import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box, Typography, Card, CardContent, Grid, TextField,
  Button, MenuItem, Select, FormControl, InputLabel,
  CircularProgress, Divider, Chip, Alert,
  Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Paper, IconButton, Tooltip,
  Collapse, Dialog, DialogActions, DialogContent, DialogTitle,
} from '@mui/material';
import SearchIcon       from '@mui/icons-material/Search';
import SendIcon         from '@mui/icons-material/Send';
import RefreshIcon      from '@mui/icons-material/Refresh';
import ExpandMoreIcon   from '@mui/icons-material/ExpandMore';
import ExpandLessIcon   from '@mui/icons-material/ExpandLess';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import ReplayIcon from '@mui/icons-material/Replay';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import HistoryIcon from '@mui/icons-material/History';
import toast            from 'react-hot-toast';
import StatusBadge      from '../components/common/StatusBadge';
import { submitTransaction, getTransactionStatus, pollTransactionStatus } from '../api/transactionApi';
import { formatCurrency, formatDateTime, truncateTxId, detectionLayerLabel, formatConfidence } from '../utils/formatters';
import { useAuth } from '../context/AuthContext';
import { getTransactionHistory } from '../api/bankApi';
import { getPendingReviewTransactions, submitReviewDecision } from '../api/fraudApi';
import { useAppState } from '../context/AppStateContext';

const MERCHANT_CATEGORIES = ['Food', 'Electronics', 'Travel', 'Healthcare', 'Entertainment', 'Utilities', 'Other'];

function ResultRow({ tx }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <TableRow sx={{ '& > *': { borderBottom: 'unset' } }}>
        <TableCell>
          <Tooltip title={tx.transactionId}>
            <Typography variant="body2" fontWeight={600} fontFamily="monospace">
              {truncateTxId(tx.transactionId, 14)}
            </Typography>
          </Tooltip>
        </TableCell>
        <TableCell>{formatCurrency(tx.amount)}</TableCell>
        <TableCell>{tx.location || '—'}</TableCell>
        <TableCell>{tx.merchantCategory || '—'}</TableCell>
        <TableCell>
          {tx.fraudStatus
            ? <StatusBadge type="fraud" status={tx.fraudStatus} />
            : <Chip label="PENDING" size="small" />}
        </TableCell>
        <TableCell>
          {tx.overallStatus
            ? <StatusBadge type="overall" status={tx.overallStatus} />
            : '—'}
        </TableCell>
        <TableCell>{formatDateTime(tx.submittedAt)}</TableCell>
        <TableCell>
          <IconButton size="small" onClick={() => setOpen(!open)}>
            {open ? <ExpandLessIcon /> : <ExpandMoreIcon />}
          </IconButton>
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell colSpan={8} sx={{ py: 0 }}>
          <Collapse in={open} unmountOnExit>
            <Box sx={{ p: 2, bgcolor: 'grey.50', borderRadius: 1 }}>
              <Grid container spacing={2}>
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Detection Layer</Typography>
                  <Typography variant="body2" fontWeight={600}>{detectionLayerLabel(tx.detectionLayer) || '—'}</Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Estimated fraud risk</Typography>
                  <Typography variant="body2" fontWeight={600}>{formatConfidence(tx.confidenceScore)}</Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Device</Typography>
                  <Typography variant="body2" fontWeight={600}>{tx.device || '—'}</Typography>
                </Grid>
                <Grid item xs={6} sm={3}>
                  <Typography variant="caption" color="text.secondary">Analyzed At</Typography>
                  <Typography variant="body2" fontWeight={600}>{formatDateTime(tx.analyzedAt)}</Typography>
                </Grid>
                {tx.reason && (
                  <Grid item xs={12}>
                    <Typography variant="caption" color="text.secondary">Reason</Typography>
                    <Typography variant="body2">{tx.reason}</Typography>
                  </Grid>
                )}
              </Grid>
            </Box>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  );
}

export default function Transactions() {
  const { user } = useAuth();
  const { refreshBalance } = useAppState();
  const navigate = useNavigate();
  const [accountHistory, setAccountHistory] = useState([]);
  const [accountHistoryLoading, setAccountHistoryLoading] = useState(true);
  const [accountHistoryError, setAccountHistoryError] = useState('');
  const [pendingReviews, setPendingReviews] = useState([]);
  const [pendingReviewsLoading, setPendingReviewsLoading] = useState(true);
  const [pendingReviewsError, setPendingReviewsError] = useState('');
  const [reviewActionId, setReviewActionId] = useState('');
  const [approvalCandidate, setApprovalCandidate] = useState(null);

  // Legacy transaction submit form
  const [form, setForm] = useState({
    transactionId: '', amount: '', location: '', device: '', merchantCategory: 'Food',
  });
  const [submitting, setSubmitting]   = useState(false);
  const [polling, setPolling]         = useState(false);

  // Poll-by-ID form
  const [pollId, setPollId]           = useState('');
  const [pollLoading, setPollLoading] = useState(false);
  const [pollResult, setPollResult]   = useState(null);

  // Transaction history (session-local)
  const [history, setHistory] = useState([]);

  const loadAccountHistory = useCallback(async () => {
    setAccountHistoryLoading(true);
    setAccountHistoryError('');
    try {
      setAccountHistory(await getTransactionHistory());
    } catch (err) {
      setAccountHistoryError(err.response?.data?.message || 'Could not load your account activity.');
    } finally {
      setAccountHistoryLoading(false);
    }
  }, []);

  const loadPendingReviews = useCallback(async () => {
    setPendingReviewsLoading(true);
    setPendingReviewsError('');
    try {
      setPendingReviews(await getPendingReviewTransactions());
    } catch (err) {
      setPendingReviewsError(err.response?.data?.message || 'Could not load transactions awaiting your decision.');
    } finally {
      setPendingReviewsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAccountHistory();
    loadPendingReviews();
  }, [loadAccountHistory, loadPendingReviews]);

  const refreshTransactionData = () => {
    loadAccountHistory();
    loadPendingReviews();
  };

  const handleReviewAction = async (transactionId, decision) => {
    if (reviewActionId) return;
    setReviewActionId(transactionId);
    setApprovalCandidate(null);
    try {
      const saved = await submitReviewDecision(transactionId, decision);
      if (decision === 'APPROVE') {
        let payment = saved.paymentStatus ? { paymentStatus: saved.paymentStatus } : null;
        for (let attempt = 0; attempt < 12
          && (!payment || !['SUCCESS', 'FAILED', 'COMPENSATED', 'COMPENSATION_FAILED'].includes(payment.paymentStatus));
        attempt++) {
          await new Promise((resolve) => setTimeout(resolve, 1500));
          payment = await getTransactionStatus(transactionId);
        }

        if (payment?.paymentStatus === 'SUCCESS') {
          toast.success('Payment accepted and completed. The recipient was credited.');
          refreshBalance();
        } else if (payment?.paymentStatus) {
          toast.error(`Payment accepted, but its status is ${payment.paymentStatus}.`);
        } else {
          toast('Payment accepted. Processing is still in progress.', { icon: '⏳' });
        }
      } else {
        toast.success('Transaction rejected. No funds were moved.');
      }
      refreshTransactionData();
    } catch (err) {
      toast.error(err.response?.data?.message || err.message || 'Could not update this transaction.');
    } finally {
      setReviewActionId('');
    }
  };

  const copyTransactionId = async (transactionId) => {
    try {
      await navigator.clipboard.writeText(transactionId);
      toast.success('Transaction ID copied');
    } catch {
      toast.error('Could not copy the transaction ID');
    }
  };

  const handleFormChange = (e) => {
    const { name, value } = e.target;
    setForm((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.transactionId || !form.amount) {
      toast.error('Transaction ID and Amount are required');
      return;
    }

    const payload = {
      transactionId:    form.transactionId.trim(),
      userId:           user.email,
      amount:           parseFloat(form.amount),
      location:         form.location || 'Unknown',
      device:           form.device   || 'Web',
      merchantCategory: form.merchantCategory,
    };

    setSubmitting(true);
    try {
      await submitTransaction(payload);
      toast.success('Transaction submitted for fraud analysis');

      const entry = { ...payload, submittedAt: new Date().toISOString() };
      setHistory((prev) => [entry, ...prev]);

      // Poll for result
      setPolling(true);
      const final = await pollTransactionStatus(payload.transactionId);
      setHistory((prev) =>
        prev.map((t) => t.transactionId === payload.transactionId ? { ...t, ...final } : t)
      );

      if (final.fraudStatus === 'FRAUD')  toast.error('Transaction BLOCKED — fraud detected!');
      else if (final.fraudStatus === 'REVIEW') toast('Under REVIEW', { icon: '⚠️' });
      else toast.success('Transaction SAFE — approved');

      setForm({ transactionId: '', amount: '', location: '', device: '', merchantCategory: 'Food' });
    } catch (err) {
      toast.error(err.response?.data?.message || 'Submission failed');
    } finally {
      setSubmitting(false);
      setPolling(false);
    }
  };

  const handlePoll = async () => {
    if (!pollId.trim()) return;
    setPollLoading(true);
    setPollResult(null);
    try {
      const result = await getTransactionStatus(pollId.trim());
      setPollResult(result);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Transaction not found');
    } finally {
      setPollLoading(false);
    }
  };

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} mb={0.5}>Transactions</Typography>
      <Typography variant="body2" color="text.secondary" mb={3}>
        View completed account activity, submit a transaction for analysis, or look up its status by ID.
      </Typography>
      <Alert severity="info" sx={{ mb: 3 }}>
        This page's “Submit Transaction” form is for fraud-analysis demos only; it does not transfer money.
        To send a simulated UPI payment, use the Dashboard payment form.
        <Button size="small" sx={{ ml: 1 }} onClick={() => navigate('/dashboard')}>
          Go to Dashboard
        </Button>
      </Alert>

      <Card sx={{ mb: 3 }}>
        <CardContent sx={{ p: 3 }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 2, mb: 2 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <HistoryIcon color="warning" />
              <Typography variant="h6" fontWeight={700}>Transactions Awaiting Your Decision</Typography>
            </Box>
            <Button
              size="small"
              startIcon={pendingReviewsLoading ? <CircularProgress size={16} /> : <RefreshIcon />}
              disabled={pendingReviewsLoading}
              onClick={loadPendingReviews}
            >
              Refresh
            </Button>
          </Box>
          <Typography variant="body2" color="text.secondary" mb={2}>
            Review flagged payments here. Accepting a REVIEW payment sends it from your simulated bank account; rejecting it keeps funds untouched. Hard FRAUD verdicts cannot be overridden.
          </Typography>
          {pendingReviewsError && <Alert severity="error" sx={{ mb: 2 }}>{pendingReviewsError}</Alert>}
          {pendingReviewsLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 3 }}>
              <CircularProgress size={28} />
            </Box>
          ) : pendingReviews.length === 0 ? (
            <Alert severity="info">No payments are waiting for your decision.</Alert>
          ) : (
            <TableContainer component={Paper} variant="outlined" sx={{ borderRadius: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    {['Transaction ID', 'Amount', 'Payee', 'Estimated fraud risk', 'Reason', 'Actions'].map((heading) => (
                      <TableCell key={heading} sx={{ fontWeight: 700 }}>{heading}</TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {pendingReviews.map((review) => {
                    const busy = reviewActionId === review.transactionId;
                    const bankPayment = !review.paymentMode || review.paymentMode === 'BANK';
                    return (
                      <TableRow key={review.transactionId} hover>
                        <TableCell>
                          <Tooltip title={review.transactionId}>
                            <Typography variant="body2" fontFamily="monospace">
                              {truncateTxId(review.transactionId, 14)}
                            </Typography>
                          </Tooltip>
                        </TableCell>
                        <TableCell>{formatCurrency(review.amount)}</TableCell>
                        <TableCell>{review.payeeUpiId || '—'}</TableCell>
                        <TableCell>
                          {formatConfidence(review.confidenceScore)}
                          {review.detectionLayer && (
                            <Typography display="block" variant="caption" color="text.secondary">
                              {detectionLayerLabel(review.detectionLayer)}
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell>{review.reason || 'Flagged for manual review'}</TableCell>
                        <TableCell>
                          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
                            <Button
                              size="small"
                              variant="contained"
                              color="success"
                              disabled={!!reviewActionId || !bankPayment}
                              onClick={() => setApprovalCandidate(review)}
                            >
                              Accept
                            </Button>
                            <Button
                              size="small"
                              variant="outlined"
                              color="error"
                              disabled={!!reviewActionId}
                              onClick={() => handleReviewAction(review.transactionId, 'CANCEL')}
                            >
                              Reject
                            </Button>
                            {busy && <CircularProgress size={18} />}
                            {!bankPayment && (
                              <Typography variant="caption" color="text.secondary">
                                Acceptance is only available for simulated bank payments.
                              </Typography>
                            )}
                          </Box>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      <Card sx={{ mb: 3 }}>
        <CardContent sx={{ p: 3 }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 2, mb: 2 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <HistoryIcon color="primary" />
              <Typography variant="h6" fontWeight={700}>Account Activity</Typography>
            </Box>
            <Button
              size="small"
              startIcon={accountHistoryLoading ? <CircularProgress size={16} /> : <RefreshIcon />}
              disabled={accountHistoryLoading}
              onClick={refreshTransactionData}
            >
              Refresh
            </Button>
          </Box>
          <Typography variant="body2" color="text.secondary" mb={2}>
            Persisted bank ledger entries for this account. Sent and received entries appear after a SAFE payment or a payer-approved REVIEW payment completes.
          </Typography>
          {accountHistoryError && <Alert severity="error" sx={{ mb: 2 }}>{accountHistoryError}</Alert>}
          {accountHistoryLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 3 }}>
              <CircularProgress size={28} />
            </Box>
          ) : accountHistory.length === 0 ? (
            <Alert severity="info">No completed money movements yet. REVIEW and FRAUD transactions do not appear here because they move no funds.</Alert>
          ) : (
            <TableContainer component={Paper} variant="outlined" sx={{ borderRadius: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    {['Direction', 'Amount', 'Balance after', 'Transaction ID', 'Date'].map((heading) => (
                      <TableCell key={heading} sx={{ fontWeight: 700 }}>{heading}</TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {accountHistory.map((entry, index) => {
                    const direction = entry.type === 'DEBIT' ? 'Sent'
                      : entry.type === 'REFUND' ? 'Refund received' : 'Received';
                    const isOutgoing = entry.type === 'DEBIT';
                    return (
                      <TableRow key={`${entry.transactionId}-${entry.type}-${index}`} hover>
                        <TableCell>
                          <Chip
                            size="small"
                            icon={isOutgoing ? <ArrowUpwardIcon /> : <ArrowDownwardIcon />}
                            label={direction}
                            color={isOutgoing ? 'error' : 'success'}
                            variant="outlined"
                          />
                        </TableCell>
                        <TableCell sx={{ fontWeight: 700, color: isOutgoing ? 'error.main' : 'success.main' }}>
                          {isOutgoing ? '−' : '+'}{formatCurrency(entry.amount)}
                        </TableCell>
                        <TableCell>{formatCurrency(entry.balanceAfter)}</TableCell>
                        <TableCell>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                            <Tooltip title={entry.transactionId}>
                              <Typography variant="body2" fontFamily="monospace">
                                {truncateTxId(entry.transactionId, 14)}
                              </Typography>
                            </Tooltip>
                            <Tooltip title="Copy full transaction ID">
                              <IconButton
                                size="small"
                                aria-label={`Copy transaction ID ${entry.transactionId}`}
                                onClick={() => copyTransactionId(entry.transactionId)}
                              >
                                <ContentCopyIcon fontSize="inherit" />
                              </IconButton>
                            </Tooltip>
                          </Box>
                        </TableCell>
                        <TableCell>{formatDateTime(entry.appliedAt)}</TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      <Grid container spacing={3}>
        {/* Submit transaction */}
        <Grid item xs={12} md={6}>
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2.5 }}>
                <SendIcon color="primary" />
                <Typography variant="h6" fontWeight={700}>Submit Transaction</Typography>
              </Box>

              <Box component="form" onSubmit={handleSubmit} noValidate>
                <TextField
                  fullWidth label="Transaction ID" name="transactionId"
                  value={form.transactionId} onChange={handleFormChange}
                  helperText="e.g. TXN-001 (must be unique)" sx={{ mb: 2 }}
                />
                <TextField
                  fullWidth label="Amount (₹)" name="amount" type="number"
                  value={form.amount} onChange={handleFormChange}
                  inputProps={{ min: 1, step: '0.01' }} sx={{ mb: 2 }}
                />
                <TextField
                  fullWidth label="Location" name="location"
                  value={form.location} onChange={handleFormChange}
                  helperText="City or IP address" sx={{ mb: 2 }}
                />
                <TextField
                  fullWidth label="Device" name="device"
                  value={form.device} onChange={handleFormChange}
                  helperText="e.g. iPhone-14, Android-Samsung" sx={{ mb: 2 }}
                />
                <FormControl fullWidth sx={{ mb: 3 }}>
                  <InputLabel>Merchant Category</InputLabel>
                  <Select name="merchantCategory" value={form.merchantCategory}
                    label="Merchant Category" onChange={handleFormChange}>
                    {MERCHANT_CATEGORIES.map((c) => (
                      <MenuItem key={c} value={c}>{c}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <Button
                  type="submit" variant="contained" fullWidth size="large"
                  disabled={submitting || polling}
                  startIcon={(submitting || polling) ? <CircularProgress size={18} color="inherit" /> : <SendIcon />}
                  sx={{ py: 1.5 }}
                >
                  {polling ? 'Analysing…' : submitting ? 'Submitting…' : 'Submit Transaction'}
                </Button>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* Poll by ID */}
        <Grid item xs={12} md={6}>
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2.5 }}>
                <SearchIcon color="primary" />
                <Typography variant="h6" fontWeight={700}>Check Transaction Status</Typography>
              </Box>
              <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
                <TextField
                  fullWidth label="Transaction ID" value={pollId}
                  onChange={(e) => setPollId(e.target.value)}
                  placeholder="TXN-001"
                  onKeyDown={(e) => e.key === 'Enter' && handlePoll()}
                />
                <Button
                  variant="contained" onClick={handlePoll} disabled={pollLoading}
                  sx={{ minWidth: 56, px: 2 }}
                >
                  {pollLoading ? <CircularProgress size={20} color="inherit" /> : <RefreshIcon />}
                </Button>
              </Box>

              {pollResult && (
                <Box sx={{ bgcolor: 'grey.50', borderRadius: 2, p: 2 }}>
                  {[
                    ['ID',           truncateTxId(pollResult.transactionId, 20)],
                    ['Amount',       formatCurrency(pollResult.amount)],
                    ['Payer',        pollResult.payerEmail || '—'],
                    ['Payee UPI',    pollResult.payeeUpiId || '—'],
                  ].map(([label, value]) => (
                    <Box key={label} sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.75 }}>
                      <Typography variant="body2" color="text.secondary">{label}</Typography>
                      <Typography variant="body2" fontWeight={600}>{value}</Typography>
                    </Box>
                  ))}
                  <Divider sx={{ my: 1 }} />
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 0.75 }}>
                    <Typography variant="body2" color="text.secondary">Fraud Verdict</Typography>
                    <StatusBadge type="fraud" status={pollResult.fraudStatus} />
                  </Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Typography variant="body2" color="text.secondary">Overall Status</Typography>
                    <StatusBadge type="overall" status={pollResult.overallStatus} />
                  </Box>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Transaction history table */}
      {history.length > 0 && (
        <Card sx={{ mt: 3 }}>
          <CardContent sx={{ p: 3 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
              <ReplayIcon color="action" />
              <Typography variant="h6" fontWeight={700}>Analysis Submissions (This Session)</Typography>
            </Box>
            <TableContainer component={Paper} variant="outlined" sx={{ borderRadius: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow sx={{ bgcolor: 'grey.50' }}>
                    {['Transaction ID', 'Amount', 'Location', 'Category', 'Fraud', 'Status', 'Submitted', ''].map((h) => (
                      <TableCell key={h} sx={{ fontWeight: 700, fontSize: '0.78rem' }}>{h}</TableCell>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {history.map((tx) => <ResultRow key={tx.transactionId} tx={tx} />)}
                </TableBody>
              </Table>
            </TableContainer>
          </CardContent>
        </Card>
      )}

      <Dialog open={!!approvalCandidate} onClose={() => setApprovalCandidate(null)}>
        <DialogTitle>Accept and send this payment?</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1 }}>
            This proceeds with a payment that fraud detection placed on REVIEW. Your simulated bank account will be debited.
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {formatCurrency(approvalCandidate?.amount)} to {approvalCandidate?.payeeUpiId || 'the selected recipient'}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setApprovalCandidate(null)} disabled={!!reviewActionId}>
            Go back
          </Button>
          <Button
            variant="contained"
            color="success"
            disabled={!!reviewActionId}
            onClick={() => handleReviewAction(approvalCandidate.transactionId, 'APPROVE')}
          >
            Accept &amp; send
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
