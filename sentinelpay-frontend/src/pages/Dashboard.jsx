/**
 * Dashboard.jsx — Enhanced dashboard page (V3)
 *
 * V3 upgrades:
 *  - FraudExplanation panel shown in result area (WHY was this flagged)
 *  - TransactionTimeline shown alongside result (visual lifecycle)
 *  - Result panel expanded to accommodate new components
 *
 * V2 features retained:
 *  - Stats persist across navigation (via AppStateContext)
 *  - react-hook-form manages UPI form
 *  - RiskGauge for confidence score visualisation
 *  - AbortController for payee lookup cleanup
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { useForm, Controller } from 'react-hook-form';
import {
  Box, Grid, Typography, Card, CardContent,
  TextField, Button, MenuItem, Select, FormControl,
  InputLabel, CircularProgress, Divider,
  Collapse, Alert, Tabs, Tab,
  FormHelperText, Dialog, DialogActions, DialogContent, DialogTitle,
} from '@mui/material';
import SendIcon          from '@mui/icons-material/Send';
import PaymentIcon       from '@mui/icons-material/Payment';
import CheckCircleIcon   from '@mui/icons-material/CheckCircle';
import BlockIcon         from '@mui/icons-material/Block';
import HourglassTopIcon  from '@mui/icons-material/HourglassTop';
import AnalyticsIcon     from '@mui/icons-material/Analytics';
import TimelineIcon      from '@mui/icons-material/Timeline';
import toast             from 'react-hot-toast';

import { useAuth }           from '../context/AuthContext';
import { useAppState }       from '../context/AppStateContext';
import BalanceCard           from '../components/dashboard/BalanceCard';
import StatsCard             from '../components/dashboard/StatsCard';
import TransactionChart      from '../components/dashboard/TransactionChart';
import ActivityFeed          from '../components/dashboard/ActivityFeed';
import StatusBadge           from '../components/common/StatusBadge';
import RiskGauge             from '../components/fraud/RiskGauge';
import FraudExplanation      from '../components/fraud/FraudExplanation';
import TransactionTimeline   from '../components/fraud/TransactionTimeline';

import { sendUpiPayment, pollTransactionStatus } from '../api/transactionApi';
import { fetchFraudResultWithRetry, getReviewDecision, submitReviewDecision } from '../api/fraudApi';
import { getAccountByUpiId }                       from '../api/bankApi';
import { createAbortController }                   from '../api/axiosConfig';
import { formatCurrency, formatDateTime, normalizeTxRecord } from '../utils/formatters';

const PAYMENT_MODES = ['BANK', 'RAZORPAY'];
const MERCHANT_CATEGORIES = ['Food', 'Electronics', 'Travel', 'Healthcare', 'Entertainment', 'Utilities', 'Other'];
const DEMO_SCENARIOS = [
  { value: 'LIVE', label: 'Live detection (automatic context)' },
  { value: 'TYPOSQUATTING', label: 'Demo: Brand Typosquatting (amazonn@upi)' },
  { value: 'PHISHING', label: 'Demo: Phishing Impersonation (amazon_refund@upi)' },
  { value: 'MULE_SURGE', label: 'Demo: Mule Velocity Surge (mule_receiver@upi)' },
  { value: 'COLD_START_HIGH', label: 'Demo: Cold-Start High Inflow (fresh_payee@upi - ₹15,000)' },
  { value: 'NEW_DEVICE', label: 'Demo: Device anomaly (REVIEW)' },
  { value: 'REMOTE_LOCATION', label: 'Demo: Remote location (London, UK)' },
];
const POLL_INTERVAL = parseInt(process.env.REACT_APP_POLL_INTERVAL_MS || '3000', 10);
const POLL_ATTEMPTS = parseInt(process.env.REACT_APP_POLL_MAX_ATTEMPTS || '15', 10);

// ── UPI form validation rules (react-hook-form) ───────────────────────────────
const UPI_RULES = {
  payeeUpiId: {
    required:  'Payee UPI ID is required',
    pattern:   { value: /^[a-zA-Z0-9._-]+@[a-zA-Z0-9]+$/, message: 'Enter a valid UPI ID (e.g. alice@upi)' },
  },
  amount: {
    required:  'Amount is required',
    min:       { value: 1, message: 'Minimum amount is ₹1' },
    max:       { value: 1000000, message: 'Maximum amount is ₹10,00,000' },
    validate:  (v) => !isNaN(parseFloat(v)) || 'Must be a valid number',
  },
  upiPin: {
    required:  'UPI PIN is required to authorize payment',
    pattern:   { value: /^[0-9]{6}$/, message: 'UPI PIN must be exactly 6 digits' },
  },
};

function detectDeviceContext() {
  const platform = navigator.userAgentData?.platform || navigator.platform || 'Unknown platform';
  const browser = /Edg\//.test(navigator.userAgent) ? 'Edge'
    : /Chrome\//.test(navigator.userAgent) ? 'Chrome'
      : /Firefox\//.test(navigator.userAgent) ? 'Firefox'
        : /Safari\//.test(navigator.userAgent) ? 'Safari' : 'Browser';
  return `${platform} · ${browser}`.slice(0, 120);
}

function timezoneLocation() {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return zone ? `Timezone: ${zone}` : 'Timezone unavailable';
}

export default function Dashboard() {
  const { user }                       = useAuth();
  const { stats, recordTransaction, refreshBalance } = useAppState();

  // Form
  const { control, register, handleSubmit, reset, watch, setValue, formState: { errors } } =
    useForm({
      defaultValues: {
        payeeUpiId: '',
        amount: '',
        merchantCategory: 'Food',
        paymentMode: 'BANK',
        upiPin: '212006',
      },
    });

  // State
  const [payeeInfo,     setPayeeInfo]     = useState(null);
  const [lookingUp,     setLookingUp]     = useState(false);
  const [submitting,    setSubmitting]    = useState(false);
  const [polling,       setPolling]       = useState(false);
  // fraudPolling: true while waiting for the Kafka async fraud result (may take several seconds)
  const [fraudPolling,  setFraudPolling]  = useState(false);
  const [result,        setResult]        = useState(null);
  const [resultTab,     setResultTab]     = useState(0);  // 0 = Explanation, 1 = Timeline
  const [reviewDecision, setReviewDecision] = useState(null);
  const [approvalDialogOpen, setApprovalDialogOpen] = useState(false);
  const [reviewActionLoading, setReviewActionLoading] = useState(false);
  const [deviceContext] = useState(detectDeviceContext);
  const [locationContext, setLocationContext] = useState(timezoneLocation);
  const [locationStatus, setLocationStatus] = useState('Using timezone fallback');
  const [demoScenario, setDemoScenario] = useState('LIVE');
  const [paymentError, setPaymentError] = useState('');

  const handleScenarioChange = (scenarioValue) => {
    setDemoScenario(scenarioValue);
    setPaymentError('');
    if (scenarioValue === 'TYPOSQUATTING') {
      setValue('payeeUpiId', 'amazonn@upi');
      setValue('amount', '1500');
      setValue('merchantCategory', 'Electronics');
    } else if (scenarioValue === 'PHISHING') {
      setValue('payeeUpiId', 'amazon_refund@upi');
      setValue('amount', '2500');
      setValue('merchantCategory', 'Other');
    } else if (scenarioValue === 'MULE_SURGE') {
      setValue('payeeUpiId', 'mule_receiver@upi');
      setValue('amount', '800');
      setValue('merchantCategory', 'Other');
    } else if (scenarioValue === 'COLD_START_HIGH') {
      setValue('payeeUpiId', 'fresh_payee@upi');
      setValue('amount', '15000');
      setValue('merchantCategory', 'Electronics');
    } else if (scenarioValue === 'LIVE') {
      setValue('payeeUpiId', '');
      setValue('amount', '');
    }
  };

  const payeeUpiId      = watch('payeeUpiId');
  const lookupAbort     = useRef(null);
  // Abort controller for the fraud-result polling loop — cancelled on unmount
  const fraudPollAbort  = useRef(null);
  // Tracks whether the component is still mounted — prevents setState after unmount
  // when the user navigates away mid-poll.
  const isMountedRef    = useRef(true);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      lookupAbort.current?.abort();
      // Cancel any in-flight fraud poll when the component unmounts
      fraudPollAbort.current?.abort();
    };
  }, []);

  // Device data is derived from the browser. Location uses the browser's
  // permission-based Geolocation API; if permission is unavailable, the app
  // uses a truthful timezone fallback rather than asking the payer to type data.
  useEffect(() => {
    if (!navigator.geolocation) return;
    setLocationStatus('Detecting location…');
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        const location = `Geo: ${coords.latitude.toFixed(2)}, ${coords.longitude.toFixed(2)}`;
        setLocationContext(location);
        setLocationStatus('Approximate browser location detected');
      },
      () => setLocationStatus('Location permission unavailable — using timezone fallback'),
      { enableHighAccuracy: false, timeout: 7000, maximumAge: 300000 },
    );
  }, []);

  // ── Payee lookup on blur ──────────────────────────────────────────────────
  const handlePayeeLookup = useCallback(async () => {
    const upi = payeeUpiId?.trim();
    if (!upi || !/^[a-zA-Z0-9._-]+@[a-zA-Z0-9]+$/.test(upi)) return;

    lookupAbort.current?.abort();
    lookupAbort.current = createAbortController();

    setLookingUp(true);
    setPayeeInfo(null);
    try {
      const acc = await getAccountByUpiId(upi);
      setPayeeInfo(acc);
    } catch {
      // Silently ignore — backend validates on submit
    } finally {
      setLookingUp(false);
    }
  }, [payeeUpiId]);

  // ── Submit UPI payment ────────────────────────────────────────────────────
  const onSubmit = async (formData) => {
    setSubmitting(true);
    setResult(null);
    setReviewDecision(null);
    setApprovalDialogOpen(false);
    setResultTab(0);
    setPaymentError('');

    try {
      let device = deviceContext;
      let location = locationContext;
      if (demoScenario === 'NEW_DEVICE') {
        device = `${deviceContext} · Demo new device`;
      } else if (demoScenario === 'REMOTE_LOCATION') {
        location = 'Demo remote location: London';
      }

      // Step 1: Submit payment (202 Accepted — queues Kafka event).
      // Device and location are automatically derived from the browser. The
      // explicit demo modes only alter them to demonstrate fraud controls.
      const resp = await sendUpiPayment({
        payeeUpiId:       formData.payeeUpiId.trim(),
        amount:           parseFloat(formData.amount),
        device,
        location,
        merchantCategory: formData.merchantCategory,
        paymentMode:      formData.paymentMode,
        upiPin:           formData.upiPin || '212006',
      });
      const txId = resp.transactionId;
      toast.success(`Payment submitted — ID: ${txId}`);

      // ── RAZORPAY: hand off to the hosted Thymeleaf payment page ───────────
      // The /pay endpoint is served directly by transaction-service (port 8081)
      // and is NOT routed through Nginx/Gateway — so we redirect the browser
      // there rather than polling.  PaymentResult.jsx handles status sync after
      // the user completes (or dismisses) the Razorpay modal.
      if (formData.paymentMode === 'RAZORPAY') {
        const paymentBase = process.env.REACT_APP_PAYMENT_PAGE_BASE_URL || 'http://localhost:8081';
        window.location.href = `${paymentBase}/pay?transactionId=${txId}`;
        return;   // browser navigates away; skip BANK polling path entirely
      }

      // Step 2: Poll transaction-service until overallStatus leaves PENDING
      setPolling(true);
      const txStatus = await pollTransactionStatus(txId, POLL_ATTEMPTS, POLL_INTERVAL);

      // Step 3: Poll for FraudResult from fraud-detection-service.
      //
      // The fraud pipeline is Kafka-async — GET /api/fraud/result/{id} returns
      // HTTP 202 until the event is processed (typically 2–8 seconds).
      // A single fetch would return null, leaving confidenceScore / detectionLayer
      // / reason / analyzedAt absent and the result panel showing incomplete data.
      //
      // fetchFraudResultWithRetry polls up to 12 × 3s = 36 seconds, returning the
      // raw FraudResult object on success or null if the pipeline is still delayed.
      let fraudResultData = null;
      try {
        // Cancel any prior poll (e.g. rapid re-submit) and create a fresh signal
        fraudPollAbort.current?.abort();
        fraudPollAbort.current = new AbortController();

        setFraudPolling(true);
        fraudResultData = await fetchFraudResultWithRetry(txId, {
          signal: fraudPollAbort.current.signal,
        });

        if (!fraudResultData) {
          // Kafka pipeline is still delayed after max retries — show a soft warning.
          // We still build a partial record from txStatus so the UI isn't empty.
          toast('Fraud analysis is delayed. The result may update shortly.', { icon: '⏳' });
        }
      } catch (err) {
        // Re-thrown 404 or unexpected error — show warning but don't block the UI
        if (err.name !== 'AbortError' && err.name !== 'CanceledError') {
          toast('Fraud analysis unavailable. Please check Fraud Alerts for the final result.', { icon: '⚠️' });
        }
      } finally {
        if (isMountedRef.current) setFraudPolling(false);
      }

      // Guard: component may have unmounted while we were polling (user navigated away).
      // If so, skip all remaining state updates — AbortController already stopped
      // the network requests, but we still need to avoid calling setState here.
      if (!isMountedRef.current) return;

      // Step 4: Build a fully-normalized record using the single source-of-truth utility.
      // normalizeTxRecord merges both DTOs, resolves field-name inconsistencies
      // (FraudResult.status → fraudStatus), and guarantees all fields exist.
      const txRecord = normalizeTxRecord(
        { ...txStatus, transactionId: txId,
          amount:    txStatus.amount    ?? parseFloat(formData.amount),
          payeeUpiId: txStatus.payeeUpiId ?? formData.payeeUpiId.trim() },
        fraudResultData,
      );

      setResult(txRecord);
      if (txRecord.fraudStatus === 'REVIEW') {
        try {
          setReviewDecision(await getReviewDecision(txId));
        } catch (decisionError) {
          toast.error(decisionError.response?.data?.message
            || 'Could not load the review action. You can retry from Fraud Alerts.');
        }
      }

      // Step 5: Push to global state — updates Fraud Alerts, Analytics, and Stats
      recordTransaction(txRecord);
      if (txRecord.overallStatus === 'COMPLETE') {
        refreshBalance();
      }

      // Step 6: Toast verdict
      if (txRecord.fraudStatus === 'FRAUD') {
        toast.error('Transaction BLOCKED — fraud detected!');
      } else if (txRecord.fraudStatus === 'REVIEW') {
        toast('Transaction flagged for REVIEW', { icon: '⚠️' });
      } else if (txRecord.overallStatus === 'COMPLETE') {
        toast.success('Payment completed successfully!');
      }

      reset({ payeeUpiId: '', amount: '', merchantCategory: 'Food', paymentMode: 'BANK' });
      setPayeeInfo(null);
    } catch (err) {
      if (isMountedRef.current) {
        const errorMsg =
          err.response?.data?.message ||
          err.response?.data?.error ||
          err.message ||
          'Payment submission failed';
        setPaymentError(errorMsg);
        toast.error(errorMsg);
      }
    } finally {
      if (isMountedRef.current) {
        setSubmitting(false);
        setPolling(false);
      }
    }
  };

  const handleReviewDecision = async (decision) => {
    if (!result?.transactionId || reviewActionLoading) return;
    setApprovalDialogOpen(false);
    setReviewActionLoading(true);
    try {
      const saved = await submitReviewDecision(result.transactionId, decision);
      setReviewDecision(saved);

      if (decision === 'APPROVE') {
        const paymentStatus = await pollTransactionStatus(
          result.transactionId, POLL_ATTEMPTS, POLL_INTERVAL,
        );
        const updated = {
          ...result,
          ...paymentStatus,
          fraudStatus: result.fraudStatus,
          confidenceScore: result.confidenceScore,
          detectionLayer: result.detectionLayer,
          reason: result.reason,
        };
        setResult(updated);
        recordTransaction(updated);
        if (paymentStatus.paymentStatus === 'SUCCESS'
            || paymentStatus.overallStatus === 'COMPLETE') {
          setReviewDecision({ ...saved, paymentStatus: 'SUCCESS' });
          refreshBalance();
          toast.success('Payment sent successfully. The recipient has been credited.');
        } else {
          toast.error(paymentStatus.paymentFailureReason
            || `Approval recorded, but payment status is ${paymentStatus.paymentStatus || 'still processing'}.`);
        }
      } else {
        toast.success('Payment cancelled. No money was moved.');
      }
    } catch (error) {
      toast.error(error.response?.data?.message || error.message || 'Could not process your review decision');
    } finally {
      setReviewActionLoading(false);
    }
  };

  // ── Status icon ───────────────────────────────────────────────────────────
  const statusIcon = (status) => {
    if (status === 'COMPLETE') return <CheckCircleIcon color="success" />;
    if (['BLOCKED', 'FAILED', 'CRITICAL'].includes(status)) return <BlockIcon color="error" />;
    return <HourglassTopIcon color="warning" />;
  };

  const resultBorderColor = result
    ? result.fraudStatus === 'SAFE' ? 'success.light' : result.fraudStatus === 'FRAUD' ? 'error.light' : 'warning.light'
    : 'divider';

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} mb={0.5}>
        Welcome back, {user?.name?.split(' ')[0]} 👋
      </Typography>
      <Typography variant="body2" color="text.secondary" mb={3}>
        Here's your SentinelPay fraud detection dashboard.
      </Typography>

      {/* ── Stats row ─────────────────────────────────────────────────────── */}
      <Grid container spacing={2} mb={3}>
        <Grid item xs={12} md={4}>
          <BalanceCard />
        </Grid>
        <Grid item xs={6} sm={4} md={2.67}>
          <StatsCard icon={<CheckCircleIcon />} title="Safe"          value={stats.safe}    color="success" />
        </Grid>
        <Grid item xs={6} sm={4} md={2.67}>
          <StatsCard icon={<BlockIcon />}        title="Blocked"       value={stats.blocked} color="error" />
        </Grid>
        <Grid item xs={6} sm={4} md={2.67}>
          <StatsCard icon={<PaymentIcon />}      title="Under Review"  value={stats.review}  color="warning" />
        </Grid>
      </Grid>

      {/* ── UPI send + result ─────────────────────────────────────────────── */}
      <Grid container spacing={3} mb={3}>
        <Grid item xs={12} md={6}>
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2.5 }}>
                <SendIcon color="primary" />
                <Typography variant="h6" fontWeight={700}>Send Money (UPI)</Typography>
              </Box>

              <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
                {/* Payee UPI */}
                <TextField
                  fullWidth
                  label="Payee UPI ID"
                  placeholder="alice@upi"
                  {...register('payeeUpiId', UPI_RULES.payeeUpiId)}
                  onBlur={handlePayeeLookup}
                  error={!!errors.payeeUpiId}
                  helperText={errors.payeeUpiId?.message || 'e.g. alice@upi'}
                  sx={{ mb: 1.5 }}
                  InputProps={{
                    endAdornment: lookingUp && <CircularProgress size={18} />,
                  }}
                />

                {payeeInfo && (
                  <Alert severity="info" sx={{ mb: 1.5, borderRadius: 2, py: 0.5 }}>
                    Payee resolved: <strong>{payeeInfo.name || payeeInfo.userId}</strong>
                  </Alert>
                )}

                {/* Amount */}
                <TextField
                  fullWidth
                  label="Amount (₹)"
                  type="number"
                  {...register('amount', UPI_RULES.amount)}
                  error={!!errors.amount}
                  helperText={errors.amount?.message || 'Payments above ₹10,000 require your confirmation before funds move'}
                  sx={{ mb: 1.5 }}
                  inputProps={{ min: 1, step: '0.01' }}
                />

                <Alert severity="info" sx={{ mb: 1.5, borderRadius: 2 }}>
                  <Typography variant="body2" fontWeight={700}>Fraud context is detected automatically</Typography>
                  <Typography variant="caption" display="block">
                    Device: {deviceContext}
                  </Typography>
                  <Typography variant="caption" display="block">
                    Location: {locationContext} · {locationStatus}
                  </Typography>
                </Alert>

                <FormControl fullWidth sx={{ mb: 1.5 }}>
                  <InputLabel>Demo Scenario</InputLabel>
                  <Select
                    value={demoScenario}
                    label="Demo Scenario"
                    onChange={(event) => handleScenarioChange(event.target.value)}
                  >
                    {DEMO_SCENARIOS.map((scenario) => (
                      <MenuItem key={scenario.value} value={scenario.value}>{scenario.label}</MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    Demo modes are explicit simulations. For remote-location blocking, first complete one LIVE safe payment.
                  </FormHelperText>
                </FormControl>

                <FormControl fullWidth sx={{ mb: 1.5 }} error={!!errors.merchantCategory}>
                  <InputLabel>Merchant Category</InputLabel>
                  <Controller
                    name="merchantCategory"
                    control={control}
                    rules={{ required: 'Merchant category is required for fraud analysis' }}
                    render={({ field }) => (
                      <Select {...field} label="Merchant Category">
                        {MERCHANT_CATEGORIES.map((category) => (
                          <MenuItem key={category} value={category}>{category}</MenuItem>
                        ))}
                      </Select>
                    )}
                  />
                  {errors.merchantCategory && (
                    <FormHelperText>{errors.merchantCategory.message}</FormHelperText>
                  )}
                </FormControl>

                {/* Payment mode */}
                <FormControl fullWidth sx={{ mb: 2 }}>
                  <InputLabel>Payment Mode</InputLabel>
                  <Controller
                    name="paymentMode"
                    control={control}
                    render={({ field }) => (
                      <Select {...field} label="Payment Mode">
                        {PAYMENT_MODES.map((m) => (
                          <MenuItem key={m} value={m}>{m}</MenuItem>
                        ))}
                      </Select>
                    )}
                  />
                </FormControl>

                {/* 6-Digit UPI PIN */}
                <TextField
                  fullWidth
                  label="6-Digit UPI PIN"
                  type="password"
                  placeholder="212006"
                  {...register('upiPin', UPI_RULES.upiPin)}
                  error={!!errors.upiPin}
                  helperText={errors.upiPin?.message || 'Default UPI PIN: 212006 (Change anytime in Profile)'}
                  inputProps={{
                    maxLength: 6,
                    inputMode: 'numeric',
                    pattern: '[0-9]*',
                    style: { letterSpacing: '4px', fontWeight: 700 },
                  }}
                  sx={{ mb: 3 }}
                />

                {paymentError && (
                  <Alert severity="error" sx={{ mb: 2, borderRadius: 2 }}>
                    {paymentError}
                  </Alert>
                )}

                <Button
                  type="submit"
                  variant="contained"
                  fullWidth
                  size="large"
                  disabled={submitting || polling || fraudPolling}
                  startIcon={
                    (submitting || polling || fraudPolling)
                      ? <CircularProgress size={20} color="inherit" />
                      : <SendIcon />
                  }
                  sx={{ py: 1.5 }}
                >
                  {fraudPolling  ? 'Detecting fraud…'
                   : polling     ? 'Analysing…'
                   : submitting  ? 'Submitting…'
                   :               'Send Payment'}
                </Button>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* Result panel — V3: FraudExplanation + TransactionTimeline tabs */}
        <Grid item xs={12} md={6}>
          <Collapse in={!!result} unmountOnExit>
            {result && (
              <Card sx={{ border: '2px solid', borderColor: resultBorderColor }}>
                <CardContent sx={{ p: 0 }}>
                  {/* Quick header */}
                  <Box sx={{ px: 3, pt: 2.5, pb: 1.5, display: 'flex', alignItems: 'center', gap: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}>
                    {statusIcon(result.overallStatus)}
                    <Box sx={{ flexGrow: 1 }}>
                      <Typography variant="h6" fontWeight={700} lineHeight={1}>Transaction Result</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {result.transactionId?.slice(0, 16)}…
                      </Typography>
                    </Box>
                    <Box sx={{ display: 'flex', gap: 0.5, flexDirection: 'column', alignItems: 'flex-end' }}>
                      <StatusBadge type="fraud"   status={result.fraudStatus}   size="small" />
                      <StatusBadge type="overall" status={result.overallStatus} size="small" />
                    </Box>
                  </Box>

                  {result.fraudStatus === 'REVIEW' && (
                    <Alert severity="warning" sx={{ mx: 3, mt: 2, borderRadius: 2 }}>
                      <Typography variant="subtitle2" fontWeight={700} gutterBottom>
                        This payment is waiting for your decision
                      </Typography>
                      <Typography variant="body2" sx={{ mb: 1.5 }}>
                        No money has moved. If you recognize this payment, approve it to debit your simulated bank account and credit the recipient. This overrides the REVIEW hold for this payment only. FRAUD verdicts cannot be approved here.
                      </Typography>
                      {reviewDecision?.decision === 'APPROVE' ? (
                        <Typography variant="body2" fontWeight={700}>
                          {reviewDecision.paymentStatus === 'SUCCESS'
                            ? 'Approved — payment completed and recipient credited.'
                            : `Approval recorded${reviewDecision.paymentStatus
                              ? ` — payment ${reviewDecision.paymentStatus.toLowerCase()}.`
                              : '; checking payment status…'}`}
                        </Typography>
                      ) : reviewDecision?.decision === 'CANCEL' ? (
                        <Typography variant="body2" fontWeight={700}>
                          You cancelled this payment. No money was moved.
                        </Typography>
                      ) : (
                        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                          <Button
                            size="small"
                            variant="contained"
                            color="warning"
                            startIcon={<CheckCircleIcon />}
                            disabled={reviewActionLoading}
                            onClick={() => setApprovalDialogOpen(true)}
                          >
                            Approve &amp; send
                          </Button>
                          <Button
                            size="small"
                            variant="outlined"
                            color="inherit"
                            disabled={reviewActionLoading}
                            onClick={() => handleReviewDecision('CANCEL')}
                          >
                            Cancel payment
                          </Button>
                          {reviewActionLoading && <CircularProgress size={18} />}
                        </Box>
                      )}
                    </Alert>
                  )}

                  {/* Risk gauge + key metadata */}
                  <Box sx={{ px: 3, py: 1.5, display: 'flex', gap: 2, alignItems: 'center', borderBottom: '1px solid', borderColor: 'divider' }}>
                    <RiskGauge
                      score={result.confidenceScore}
                      size={0.75}
                    />
                    <Box sx={{ flexGrow: 1 }}>
                      {[
                        ['Amount',    formatCurrency(result.amount)],
                        ['Payee',     result.payeeUpiId || '—'],
                        ['Settled',   formatDateTime(result.analyzedAt)],
                      ].map(([label, value]) => (
                        <Box key={label} sx={{ mb: 0.5 }}>
                          <Typography variant="caption" color="text.secondary">{label}</Typography>
                          <Typography variant="body2" fontWeight={600} fontSize="0.8rem">{value || '—'}</Typography>
                        </Box>
                      ))}
                    </Box>
                  </Box>

                  {/* Tabs: Explanation | Timeline */}
                  <Tabs
                    value={resultTab}
                    onChange={(_, v) => setResultTab(v)}
                    sx={{ borderBottom: '1px solid', borderColor: 'divider', minHeight: 40 }}
                    TabIndicatorProps={{ sx: { height: 3 } }}
                  >
                    <Tab
                      label="Why Flagged?"
                      icon={<AnalyticsIcon sx={{ fontSize: 16 }} />}
                      iconPosition="start"
                      sx={{ minHeight: 40, fontSize: '0.8rem', textTransform: 'none', fontWeight: 600 }}
                    />
                    <Tab
                      label="Timeline"
                      icon={<TimelineIcon sx={{ fontSize: 16 }} />}
                      iconPosition="start"
                      sx={{ minHeight: 40, fontSize: '0.8rem', textTransform: 'none', fontWeight: 600 }}
                    />
                  </Tabs>

                  <Box sx={{ p: 2, maxHeight: 320, overflowY: 'auto' }}>
                    {resultTab === 0 && (
                      <FraudExplanation result={{
                        transactionId:   result.transactionId,
                        fraudStatus:     result.fraudStatus,
                        reason:          result.reason,
                        confidenceScore: result.confidenceScore,
                        detectionLayer:  result.detectionLayer,
                        reviewNotes:     result.reviewNotes,
                        analyzedAt:      result.analyzedAt,
                      }} />
                    )}
                    {resultTab === 1 && (
                      <TransactionTimeline
                        result={{
                          fraudStatus:    result.fraudStatus,
                          detectionLayer: result.detectionLayer,
                        }}
                        loading={false}
                        txId={result.transactionId}
                      />
                    )}
                  </Box>
                </CardContent>
              </Card>
            )}
          </Collapse>

          {/* Polling in-progress — show timeline in "active" state */}
          {!result && (polling || fraudPolling) && (
            <Card sx={{ border: '2px solid', borderColor: 'primary.light' }}>
              <CardContent sx={{ p: 3 }}>
                {/* Phase-specific status message above the timeline */}
                <Alert
                  severity="info"
                  sx={{ mb: 2, borderRadius: 2 }}
                  icon={<CircularProgress size={16} color="inherit" />}
                >
                  {fraudPolling
                    ? 'Analyzing transaction… Fraud detection is processing your payment.'
                    : 'Submitting to payment network…'}
                </Alert>
                <TransactionTimeline result={null} loading={true} txId={null} />
              </CardContent>
            </Card>
          )}

          {!result && !polling && (
            <Box sx={{ height: '100%', minHeight: 240, border: '2px dashed', borderColor: 'divider', borderRadius: 3, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', gap: 1, color: 'text.secondary' }}>
              <PaymentIcon sx={{ fontSize: 48, opacity: 0.2 }} />
              <Typography variant="body2">Transaction result will appear here</Typography>
              <Typography variant="caption" color="text.disabled">Submit a payment above</Typography>
            </Box>
          )}
        </Grid>
      </Grid>

      <Dialog open={approvalDialogOpen} onClose={() => setApprovalDialogOpen(false)}>
        <DialogTitle>Approve and send this payment?</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1 }}>
            This explicitly overrides the automated REVIEW hold. SentinelPay will attempt to debit your simulated bank account and credit the recipient.
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {formatCurrency(result?.amount)} to {result?.payeeUpiId || 'the selected UPI recipient'}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setApprovalDialogOpen(false)} disabled={reviewActionLoading}>
            Go back
          </Button>
          <Button
            variant="contained"
            color="warning"
            disabled={reviewActionLoading}
            onClick={() => handleReviewDecision('APPROVE')}
          >
            {reviewActionLoading ? 'Sending…' : 'Confirm & send'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* ── Charts + Activity feed ────────────────────────────────────────── */}
      <Grid container spacing={3}>
        <Grid item xs={12} md={5}>
          <TransactionChart />
        </Grid>
        <Grid item xs={12} md={7}>
          <ActivityFeed />
        </Grid>
      </Grid>
    </Box>
  );
}
