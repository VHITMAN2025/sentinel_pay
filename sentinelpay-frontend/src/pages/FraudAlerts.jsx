/**
 * FraudAlerts.jsx — Enhanced fraud analysis page (v2)
 *
 * Upgrades over v1:
 *  - RiskGauge displays estimated fraud probability
 *  - FraudTrendChart shows hourly safe/blocked/review breakdown
 *  - AlertsPanel provides live alert feed with simulate capability
 *  - react-hook-form manages form state (replaces useState + manual validate)
 *  - Skeleton loading states while fetching
 *  - AbortController prevents stale state on unmount
 */

import React, { useState, useEffect, useRef } from 'react';
import { useForm } from 'react-hook-form';
import {
  Box, Typography, Card, CardContent, Grid, TextField,
  Button, CircularProgress, Divider, Alert,
  Chip, Dialog, DialogActions, DialogContent, DialogTitle, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, Paper, Tooltip,
} from '@mui/material';
import ShieldIcon        from '@mui/icons-material/Shield';
import SearchIcon        from '@mui/icons-material/Search';
import InfoOutlinedIcon  from '@mui/icons-material/InfoOutlined';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined';
import toast             from 'react-hot-toast';
import StatusBadge       from '../components/common/StatusBadge';
import RiskGauge         from '../components/fraud/RiskGauge';
import FraudTrendChart   from '../components/fraud/FraudTrendChart';
import AlertsPanel       from '../components/fraud/AlertsPanel';
import { FraudResultSkeleton } from '../components/common/PageSkeleton';
import {
  getFraudResult, getUserFraudHistory, getReviewDecision, submitReviewDecision,
} from '../api/fraudApi';
import { createAbortController } from '../api/axiosConfig';
import {
  formatDateTime, truncateTxId, detectionLayerLabel, formatCurrency,
  normalizeTxRecord,
} from '../utils/formatters';
import { useAuth }     from '../context/AuthContext';
import { useAppState } from '../context/AppStateContext';

export default function FraudAlerts() {
  const { user }   = useAuth();
  const { stats, recordTransaction, refreshBalance } = useAppState();

  // Fraud result lookup
  const { register: regTx, handleSubmit: handleTxSubmit, formState: { errors: txErrors } } = useForm();
  const [txLoading, setTxLoading] = useState(false);
  const [txResult,  setTxResult]  = useState(null);
  const [txPending, setTxPending] = useState(false);
  const [reviewDecision, setReviewDecision] = useState(null);
  const [reviewActionLoading, setReviewActionLoading] = useState(false);
  const [approvalDialogOpen, setApprovalDialogOpen] = useState(false);
  const txAbortRef                = useRef(null);

  // User history lookup
  const { register: regHist, handleSubmit: handleHistSubmit, formState: { errors: histErrors } } = useForm({
    defaultValues: { historyUserId: user?.email || '' },
  });
  const [historyLoading, setHistoryLoading] = useState(false);
  const [history,        setHistory]        = useState(null);

  // Cleanup on unmount
  useEffect(() => {
    return () => { txAbortRef.current?.abort(); };
  }, []);

  // ── Fetch fraud result ──────────────────────────────────────────────────────
  const onFetchResult = async ({ transactionId }) => {
    txAbortRef.current?.abort();
    txAbortRef.current = createAbortController();

    setTxLoading(true);
    setTxResult(null);
    setTxPending(false);
    setReviewDecision(null);

    try {
      const { status, data } = await getFraudResult(transactionId.trim());
      if (status === 202) {
        setTxPending(true);
        toast('Analysis still in progress — retry in a few seconds', { icon: '⏳' });
      } else {
        // Normalize FraudResult (which uses `status`) into a consistent record
        // (which uses `fraudStatus`) so the UI and Analytics both receive
        // the same field layout regardless of which API sourced the data.
        const normalized = normalizeTxRecord(null, data);
        setTxResult(normalized);
        if (normalized.fraudStatus === 'REVIEW') {
          try {
            setReviewDecision(await getReviewDecision(normalized.transactionId));
          } catch (decisionError) {
            if (decisionError.response?.status === 403) {
              setReviewDecision({ decision: 'NOT_ALLOWED' });
            } else {
              throw decisionError;
            }
          }
        }
        // Push to global state → Analytics charts + session stats update
        recordTransaction(normalized);
      }
    } catch (err) {
      if (err.name !== 'AbortError' && err.name !== 'CanceledError') {
        toast.error(err.message || 'Could not fetch fraud result');
      }
    } finally {
      setTxLoading(false);
    }
  };

  // ── Fetch history ──────────────────────────────────────────────────────────
  const onFetchHistory = async ({ historyUserId }) => {
    setHistoryLoading(true);
    setHistory(null);
    try {
      const data = await getUserFraudHistory(historyUserId.trim());
      setHistory(data);
    } catch (err) {
      toast.error(err.message || 'No history found for this user');
    } finally {
      setHistoryLoading(false);
    }
  };

  const handleReviewDecision = async (decision) => {
    if (!txResult || reviewActionLoading) return;
    setApprovalDialogOpen(false);
    setReviewActionLoading(true);
    try {
      const saved = await submitReviewDecision(txResult.transactionId, decision);
      setReviewDecision(saved);
      if (decision === 'APPROVE' && saved.paymentStatus === 'SUCCESS') {
        refreshBalance();
        toast.success('Payment approved and the recipient was credited.');
      } else if (decision === 'APPROVE') {
        toast(saved.paymentStatus
          ? `Approval recorded. Payment status: ${saved.paymentStatus}.`
          : 'Approval recorded. Payment processing is still in progress.', { icon: '⏳' });
      } else {
        toast.success('Cancellation recorded. No funds were moved.');
      }
    } catch (err) {
      toast.error(err.response?.data?.message || err.message || 'Could not record your decision');
    } finally {
      setReviewActionLoading(false);
    }
  };

  // ── Donut stat ────────────────────────────────────────────────────────────
  const fraudRate = stats.total > 0 ? ((stats.blocked / stats.total) * 100).toFixed(1) : '—';

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} mb={0.5}>Fraud Alerts</Typography>
      <Typography variant="body2" color="text.secondary" mb={3}>
        Analyse fraud verdicts, monitor alerts, and review behaviour history.
      </Typography>

      {/* ── Session stats ─────────────────────────────────────────────────── */}
      <Grid container spacing={2} mb={3}>
        {[
          { label: 'Total Analysed', value: stats.total,   color: 'default' },
          { label: 'Safe',           value: stats.safe,    color: 'success' },
          { label: 'Blocked',        value: stats.blocked, color: 'error' },
          { label: 'Under Review',   value: stats.review,  color: 'warning' },
          { label: 'Fraud Rate',     value: fraudRate === '—' ? '—' : `${fraudRate}%`, color: fraudRate > 20 ? 'error' : 'default' },
        ].map(({ label, value, color }) => (
          <Grid key={label} item xs={6} sm={4} md={2.4}>
            <Card sx={{ textAlign: 'center', py: 1.5 }}>
              <CardContent sx={{ p: '12px !important' }}>
                <Typography variant="h4" fontWeight={800}
                  color={color !== 'default' ? `${color}.main` : 'text.primary'}>
                  {value}
                </Typography>
                <Typography variant="caption" color="text.secondary">{label}</Typography>
              </CardContent>
            </Card>
          </Grid>
        ))}
      </Grid>

      {/* ── Trend chart + Alerts panel ────────────────────────────────────── */}
      <Grid container spacing={3} mb={3}>
        <Grid item xs={12} md={7}>
          <FraudTrendChart />
        </Grid>
        <Grid item xs={12} md={5}>
          <AlertsPanel maxHeight={300} />
        </Grid>
      </Grid>

      {/* ── Verdict lookup + Risk gauge ───────────────────────────────────── */}
      <Grid container spacing={3} mb={3}>
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2.5 }}>
                <ShieldIcon color="primary" />
                <Typography variant="h6" fontWeight={700}>Fraud Verdict Lookup</Typography>
              </Box>

              <Box component="form" onSubmit={handleTxSubmit(onFetchResult)} sx={{ display: 'flex', gap: 1, mb: 2 }}>
                <TextField
                  fullWidth
                  label="Transaction ID"
                  placeholder="TXN-001"
                  {...regTx('transactionId', { required: 'Transaction ID is required' })}
                  error={!!txErrors.transactionId}
                  helperText={txErrors.transactionId?.message}
                />
                <Button
                  type="submit"
                  variant="contained"
                  disabled={txLoading}
                  sx={{ minWidth: 56 }}
                >
                  {txLoading ? <CircularProgress size={20} color="inherit" /> : <SearchIcon />}
                </Button>
              </Box>

              {txPending && (
                <Alert severity="info" sx={{ borderRadius: 2, mb: 2 }}>
                  The fraud pipeline is still processing. Retry in a few seconds.
                </Alert>
              )}

              {txLoading && <FraudResultSkeleton />}

              {txResult && !txLoading && (
                <Box>
                  {/* ID + Status header */}
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                    <Box>
                      <Typography variant="caption" color="text.secondary">Transaction ID</Typography>
                      <Typography variant="body2" fontWeight={700} fontFamily="monospace">
                        {truncateTxId(txResult.transactionId, 24)}
                      </Typography>
                    </Box>
                    <StatusBadge type="fraud" status={txResult.fraudStatus} size="medium" />
                  </Box>

                  <Divider sx={{ mb: 2 }} />

                  {/* Risk gauge + metadata side by side */}
                  <Box sx={{ display: 'flex', gap: 3, alignItems: 'flex-start', mb: 2 }}>
                    <Box sx={{ flexShrink: 0 }}>
                      <RiskGauge score={txResult.confidenceScore} size="small" />
                    </Box>
                    <Box sx={{ flexGrow: 1 }}>
                      {[
                        ['User',            txResult.userId],
                        ['Detection Layer', detectionLayerLabel(txResult.detectionLayer)],
                        ['Analyzed At',     formatDateTime(txResult.analyzedAt)],
                      ].map(([label, value]) => (
                        <Box key={label} sx={{ mb: 0.75 }}>
                          <Typography variant="caption" color="text.secondary">{label}</Typography>
                          <Typography variant="body2" fontWeight={600}>{value || '—'}</Typography>
                        </Box>
                      ))}
                    </Box>
                  </Box>

                  {txResult.reason && (
                    <Alert
                      severity={txResult.fraudStatus === 'FRAUD' ? 'error' : txResult.fraudStatus === 'REVIEW' ? 'warning' : 'success'}
                      sx={{ mb: 1.5, borderRadius: 2 }}
                    >
                      {txResult.reason}
                    </Alert>
                  )}

                  {txResult.reviewNotes && (
                    <Alert severity="warning" icon={<InfoOutlinedIcon />} sx={{ mb: 2, borderRadius: 2 }}>
                      <strong>Review Notes:</strong> {txResult.reviewNotes}
                    </Alert>
                  )}

                  {txResult.fraudStatus === 'REVIEW' && (
                    <Alert severity="info" sx={{ mt: 2, borderRadius: 2 }}>
                      <Typography variant="subtitle2" fontWeight={700} gutterBottom>
                        Your decision
                      </Typography>
                      <Typography variant="body2" sx={{ mb: 1.5 }}>
                        No money has moved. You can approve this specific payment to continue as a simulated BANK transfer, or cancel it. Approval overrides the automated REVIEW hold; FRAUD transactions cannot be approved here.
                      </Typography>
                      {reviewDecision?.decision === 'NOT_ALLOWED' ? (
                        <Typography variant="body2">Only the payer can record a decision for this transaction.</Typography>
                      ) : (
                        <>
                          {reviewDecision?.decision === 'APPROVE' ? (
                            <Typography variant="body2" fontWeight={700}>
                              {reviewDecision.paymentStatus === 'SUCCESS'
                                ? 'Approved — payment completed and recipient credited.'
                                : `Approval recorded${reviewDecision.paymentStatus ? ` — payment ${reviewDecision.paymentStatus.toLowerCase()}.` : '; payment is processing.'}`}
                            </Typography>
                          ) : reviewDecision?.decision === 'CANCEL' ? (
                            <Typography variant="body2" fontWeight={700}>
                              You cancelled this payment attempt. No funds were moved.
                            </Typography>
                          ) : (
                            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                              <Button
                                size="small"
                                variant="contained"
                                color="warning"
                                startIcon={<CheckCircleOutlineIcon />}
                                disabled={reviewActionLoading}
                                onClick={() => setApprovalDialogOpen(true)}
                              >
                                Approve &amp; send
                              </Button>
                              <Button
                                size="small"
                                variant="outlined"
                                color="error"
                                startIcon={<CancelOutlinedIcon />}
                                disabled={reviewActionLoading || reviewDecision?.decision === 'CONFIRM'}
                                onClick={() => handleReviewDecision('CANCEL')}
                              >
                                Cancel this attempt
                              </Button>
                            </Box>
                          )}
                          {reviewActionLoading && <CircularProgress size={18} sx={{ ml: 1 }} />}
                        </>
                      )}
                    </Alert>
                  )}
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Dialog open={approvalDialogOpen} onClose={() => setApprovalDialogOpen(false)}>
          <DialogTitle>Approve this reviewed payment?</DialogTitle>
          <DialogContent>
            <Typography variant="body2" sx={{ mb: 1 }}>
              This overrides the automated REVIEW hold for this transaction. SentinelPay will attempt to debit your simulated bank account and credit the recipient.
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Transaction: {txResult?.transactionId}
              {reviewDecision?.amount ? ` · ${formatCurrency(reviewDecision.amount)}` : ''}
              {reviewDecision?.payeeUpiId ? ` · To ${reviewDecision.payeeUpiId}` : ''}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Approval is only available for REVIEW verdicts and simulated BANK payments. A FRAUD verdict is never overridden.
            </Typography>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setApprovalDialogOpen(false)} disabled={reviewActionLoading}>Go back</Button>
            <Button
              variant="contained"
              color="warning"
              onClick={() => handleReviewDecision('APPROVE')}
              disabled={reviewActionLoading}
            >
              {reviewActionLoading ? 'Processing…' : 'Confirm & send'}
            </Button>
          </DialogActions>
        </Dialog>

        {/* ── Behaviour history ───────────────────────────────────────────── */}
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2.5 }}>
                <SearchIcon color="primary" />
                <Typography variant="h6" fontWeight={700}>Behaviour History</Typography>
              </Box>

              <Box component="form" onSubmit={handleHistSubmit(onFetchHistory)} sx={{ display: 'flex', gap: 1, mb: 2 }}>
                <TextField
                  fullWidth
                  label="User ID / Email"
                  {...regHist('historyUserId', { required: 'User ID is required' })}
                  error={!!histErrors.historyUserId}
                  helperText={histErrors.historyUserId?.message}
                />
                <Button
                  type="submit"
                  variant="contained"
                  disabled={historyLoading}
                  sx={{ minWidth: 56 }}
                >
                  {historyLoading ? <CircularProgress size={20} color="inherit" /> : <SearchIcon />}
                </Button>
              </Box>

              {history && (
                <>
                  <Box sx={{ mb: 2 }}>
                    <Typography variant="body2" color="text.secondary" gutterBottom fontWeight={600}>
                      Known Locations
                    </Typography>
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                      {(history.knownLocations || []).length > 0
                        ? history.knownLocations.map((loc) => (
                          <Chip key={loc} label={loc} size="small" variant="outlined" />
                        ))
                        : <Typography variant="body2" color="text.secondary">None cached</Typography>}
                    </Box>
                  </Box>

                  <Box sx={{ mb: 2 }}>
                    <Typography variant="body2" color="text.secondary" gutterBottom fontWeight={600}>
                      Known Devices
                    </Typography>
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                      {(history.knownDevices || []).length > 0
                        ? history.knownDevices.map((d) => (
                          <Chip key={d} label={d} size="small" variant="outlined" color="info" />
                        ))
                        : <Typography variant="body2" color="text.secondary">None cached</Typography>}
                    </Box>
                  </Box>

                  {(history.recentTransactions || []).length > 0 && (
                    <>
                      <Divider sx={{ my: 1.5 }} />
                      <Typography variant="body2" fontWeight={700} mb={1}>
                        Recent Transactions ({history.recentTransactions.length})
                      </Typography>
                      <TableContainer component={Paper} variant="outlined" sx={{ borderRadius: 2, maxHeight: 240, overflow: 'auto' }}>
                        <Table size="small" stickyHeader>
                          <TableHead>
                            <TableRow>
                              {['TX ID', 'Amount', 'Location', 'Time'].map((h) => (
                                <TableCell key={h}>{h}</TableCell>
                              ))}
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {history.recentTransactions.map((tx, i) => (
                              <TableRow key={i} hover>
                                <TableCell>
                                  <Tooltip title={tx.transactionId || ''}>
                                    <Typography variant="caption" fontFamily="monospace">
                                      {truncateTxId(tx.transactionId, 10)}
                                    </Typography>
                                  </Tooltip>
                                </TableCell>
                                <TableCell>
                                  <Typography variant="caption">
                                    {tx.amount != null ? formatCurrency(tx.amount) : '—'}
                                  </Typography>
                                </TableCell>
                                <TableCell>
                                  <Typography variant="caption">{tx.location || '—'}</Typography>
                                </TableCell>
                                <TableCell>
                                  <Typography variant="caption">{formatDateTime(tx.timestamp)}</Typography>
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </Table>
                      </TableContainer>
                    </>
                  )}
                </>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* ── Detection rules table ─────────────────────────────────────────── */}
      <Card>
        <CardContent sx={{ p: 3 }}>
          <Typography variant="h6" fontWeight={700} mb={2}>Detection Rules Reference</Typography>
          <TableContainer component={Paper} variant="outlined" sx={{ borderRadius: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  {['Scenario', 'Trigger', 'Layer', 'Verdict'].map((h) => (
                    <TableCell key={h}>{h}</TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {[
                  ['Large transaction',  'Amount > ₹10,000',          'Rule Engine',        'FRAUD'],
                  ['Unknown location',   'First tx from new city',    'Rule Engine',        'FRAUD'],
                  ['High velocity',      '> 10 transactions / 24 h',  'Rule Engine',        'FRAUD'],
                  ['Impossible travel',  '2 cities within 5 min',     'Behaviour History',  'FRAUD'],
                  ['Device change',      'New device detected',       'History → AI',       'REVIEW'],
                  ['Contextual risk',    'Estimated fraud risk > 60%',       'AI (Gemini)',        'FRAUD'],
                  ['Borderline risk',    'Estimated fraud risk 40% – 60%',   'AI (Gemini)',        'REVIEW'],
                  ['Low risk',           'Estimated fraud risk < 40%',       'AI (Gemini)',        'SAFE'],
                ].map(([scenario, trigger, layer, verdict]) => (
                  <TableRow key={scenario} hover>
                    <TableCell>{scenario}</TableCell>
                    <TableCell>
                      <Typography variant="body2" fontFamily="monospace" fontSize="0.78rem">{trigger}</Typography>
                    </TableCell>
                    <TableCell>{layer}</TableCell>
                    <TableCell><StatusBadge type="fraud" status={verdict} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </CardContent>
      </Card>
    </Box>
  );
}
