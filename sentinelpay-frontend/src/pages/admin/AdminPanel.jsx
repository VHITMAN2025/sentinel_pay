/**
 * AdminPanel.jsx — Enterprise Admin Operations & Intelligence Portal (V4)
 *
 * High-contrast, accessible, responsive administrative console.
 * Strictly restricted to karthik@gmail.com with ROLE_ADMIN.
 *
 * Sections:
 *  0 · Overview       — Key metrics, infrastructure health, recent audit trail
 *  1 · Users          — User registry & account details
 *  2 · Bank Accounts  — Account ledger, freeze/unfreeze toggles, balance adjustments
 *  3 · Transactions   — Bank transaction log (debits, credits, refunds)
 *  4 · Fraud Events   — Real-time fraud detection engine decisions & AI scores
 *  5 · Review Queue   — Pending transactions awaiting manual verification
 *  6 · Blocklist      — Instant-block registry (UPI, user, location, device)
 *  7 · Fraud Rules    — Live fraud threshold and pipeline tuning
 *  8 · Audit Log      — Immutable administrative event trail
 */

import React, { useEffect, useState, useCallback } from 'react';
import {
  Box, Typography, Tab, Tabs, Grid, Card,
  Chip, Alert, CircularProgress, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Paper,
  Button, IconButton, Tooltip, TextField, Dialog,
  DialogTitle, DialogContent, DialogActions, Select,
  MenuItem, FormControl, InputLabel, Divider, Avatar,
  LinearProgress, InputAdornment,
} from '@mui/material';
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings';
import PeopleIcon             from '@mui/icons-material/People';
import AccountBalanceIcon     from '@mui/icons-material/AccountBalance';
import ReceiptLongIcon        from '@mui/icons-material/ReceiptLong';
import SecurityIcon           from '@mui/icons-material/Security';
import RuleIcon               from '@mui/icons-material/Rule';
import BlockIcon              from '@mui/icons-material/Block';
import HistoryIcon            from '@mui/icons-material/History';
import BarChartIcon           from '@mui/icons-material/BarChart';
import RefreshIcon            from '@mui/icons-material/Refresh';
import CheckCircleIcon        from '@mui/icons-material/CheckCircle';
import CancelIcon             from '@mui/icons-material/Cancel';
import AddCircleIcon          from '@mui/icons-material/AddCircle';
import DeleteIcon             from '@mui/icons-material/Delete';
import LockIcon               from '@mui/icons-material/Lock';
import LockOpenIcon           from '@mui/icons-material/LockOpen';
import TrendingUpIcon         from '@mui/icons-material/TrendingUp';
import WarningAmberIcon       from '@mui/icons-material/WarningAmber';
import MonitorHeartIcon       from '@mui/icons-material/MonitorHeart';
import SavingsIcon            from '@mui/icons-material/Savings';
import SearchIcon             from '@mui/icons-material/Search';
import VerifiedUserIcon       from '@mui/icons-material/VerifiedUser';
import HubIcon                from '@mui/icons-material/Hub';
import StorageIcon            from '@mui/icons-material/Storage';
import PsychologyIcon         from '@mui/icons-material/Psychology';

import { useAuth }   from '../../context/AuthContext';
import toast         from 'react-hot-toast';
import {
  getAdminStats, getSystemHealth, getAdminUsers,
  getAdminBankAccounts, updateBankAccountStatus, adjustBankAccountBalance,
  getAdminBankTransactions, getAdminFraudTransactions, getAdminReviewQueue,
  submitAdminReview, getAdminFraudRules, updateAdminFraudRules,
  getAdminBlocklist, addToBlocklist, removeFromBlocklist, getAdminAuditLogs,
} from '../../api/adminApi';

// ── Formatters ────────────────────────────────────────────────────────────────
const fmt = (n) => Number(n || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const fmtDate = (d) => {
  if (!d) return '—';
  try {
    return new Date(d).toLocaleString('en-IN', {
      day: '2-digit', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
  } catch { return String(d); }
};

function StatusChip({ value }) {
  const map = {
    SAFE:         { bg: 'rgba(16, 185, 129, 0.15)', text: '#10b981', label: 'SAFE' },
    FRAUD:        { bg: 'rgba(239, 68, 68, 0.15)',   text: '#ef4444', label: 'FRAUD' },
    REVIEW:       { bg: 'rgba(245, 158, 11, 0.15)',  text: '#f59e0b', label: 'REVIEW' },
    ACTIVE:       { bg: 'rgba(16, 185, 129, 0.15)', text: '#10b981', label: 'ACTIVE' },
    FROZEN:       { bg: 'rgba(56, 189, 248, 0.15)',  text: '#38bdf8', label: 'FROZEN' },
    SUSPENDED:    { bg: 'rgba(239, 68, 68, 0.15)',   text: '#ef4444', label: 'SUSPENDED' },
    APPROVE:      { bg: 'rgba(16, 185, 129, 0.15)', text: '#10b981', label: 'APPROVED' },
    REJECT:       { bg: 'rgba(239, 68, 68, 0.15)',   text: '#ef4444', label: 'REJECTED' },
    ROLE_ADMIN:   { bg: 'rgba(99, 102, 241, 0.15)',  text: '#818cf8', label: 'ADMIN' },
    ROLE_USER:    { bg: 'rgba(148, 163, 184, 0.15)', text: '#94a3b8', label: 'USER' },
    UP:           { bg: 'rgba(16, 185, 129, 0.15)', text: '#10b981', label: 'ONLINE' },
    HEALTHY:      { bg: 'rgba(16, 185, 129, 0.15)', text: '#10b981', label: 'HEALTHY' },
  };

  const cfg = map[value] || { bg: 'rgba(148, 163, 184, 0.15)', text: '#94a3b8', label: value || '—' };

  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        px: 1.25,
        py: 0.35,
        borderRadius: 1.5,
        fontSize: '0.75rem',
        fontWeight: 700,
        letterSpacing: '0.03em',
        bgcolor: cfg.bg,
        color: cfg.text,
        border: `1px solid ${cfg.text}33`,
      }}
    >
      <Box component="span" sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: cfg.text }} />
      {cfg.label}
    </Box>
  );
}

function StatCard({ icon, label, value, sub, color = 'primary' }) {
  const colorMap = {
    primary: { text: '#818cf8', bg: 'rgba(99, 102, 241, 0.12)' },
    success: { text: '#34d399', bg: 'rgba(16, 185, 129, 0.12)' },
    error:   { text: '#f87171', bg: 'rgba(239, 68, 68, 0.12)' },
    warning: { text: '#fbbf24', bg: 'rgba(245, 158, 11, 0.12)' },
    info:    { text: '#38bdf8', bg: 'rgba(56, 189, 248, 0.12)' },
  };
  const themeColors = colorMap[color] || colorMap.primary;

  return (
    <Card
      sx={{
        height: '100%',
        p: 2.25,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        borderRadius: 2.5,
        transition: 'transform 0.15s ease, box-shadow 0.15s ease',
        '&:hover': { transform: 'translateY(-2px)' },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1, mb: 1.5 }}>
        <Typography variant="body2" sx={{ fontWeight: 600, color: 'text.secondary', fontSize: '0.8125rem', lineHeight: 1.3 }}>
          {label}
        </Typography>
        <Box
          sx={{
            width: 36,
            height: 36,
            borderRadius: 2,
            bgcolor: themeColors.bg,
            color: themeColors.text,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          {icon}
        </Box>
      </Box>

      <Typography variant="h4" sx={{ fontWeight: 800, color: 'text.primary', letterSpacing: '-0.02em', my: 0.5 }}>
        {value ?? '—'}
      </Typography>

      <Typography variant="caption" sx={{ color: sub ? themeColors.text : 'transparent', fontWeight: 700, minHeight: 18 }}>
        {sub || '—'}
      </Typography>
    </Card>
  );
}

function TabPanel({ children, value, index }) {
  return value === index ? (
    <Box sx={{ pt: 3 }}>{children}</Box>
  ) : null;
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 0: Overview
// ══════════════════════════════════════════════════════════════════════════════
function OverviewSection() {
  const [stats, setStats]   = useState(null);
  const [health, setHealth] = useState(null);
  const [audits, setAudits] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, h, a] = await Promise.all([
        getAdminStats().catch(() => null),
        getSystemHealth().catch(() => null),
        getAdminAuditLogs().catch(() => []),
      ]);
      setStats(s); setHealth(h); setAudits(a);
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  if (loading) return <Box sx={{ py: 6, textAlign: 'center' }}><CircularProgress /></Box>;

  const serviceIcons = {
    authService:           <VerifiedUserIcon sx={{ fontSize: 20 }} />,
    bankService:           <AccountBalanceIcon sx={{ fontSize: 20 }} />,
    transactionService:    <ReceiptLongIcon sx={{ fontSize: 20 }} />,
    fraudDetectionService: <SecurityIcon sx={{ fontSize: 20 }} />,
    redisCache:            <StorageIcon sx={{ fontSize: 20 }} />,
    kafkaCluster:          <HubIcon sx={{ fontSize: 20 }} />,
    geminiAiModel:         <PsychologyIcon sx={{ fontSize: 20 }} />,
  };

  return (
    <Box>
      {/* Action Header */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2.5 }}>
        <Typography variant="subtitle1" fontWeight={700} color="text.primary">
          Platform Activity Overview
        </Typography>
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
          Refresh Real-time Metrics
        </Button>
      </Box>

      {/* KPI Cards — Responsive 6-card grid */}
      <Grid container spacing={2.5} mb={3.5}>
        <Grid item xs={12} sm={6} md={4} xl={2}>
          <StatCard icon={<TrendingUpIcon />} label="Total Analysed" value={stats?.totalAnalysed ?? 0} color="primary" />
        </Grid>
        <Grid item xs={12} sm={6} md={4} xl={2}>
          <StatCard icon={<CheckCircleIcon />} label="Safe Transactions" value={stats?.safeCount ?? 0} color="success" />
        </Grid>
        <Grid item xs={12} sm={6} md={4} xl={2}>
          <StatCard
            icon={<BlockIcon />}
            label="Blocked (Fraud)"
            value={stats?.blockedCount ?? 0}
            color="error"
            sub={`Fraud Rate: ${stats?.fraudRate || '0.0%'}`}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={4} xl={2}>
          <StatCard icon={<WarningAmberIcon />} label="Under Review" value={stats?.reviewCount ?? 0} color="warning" />
        </Grid>
        <Grid item xs={12} sm={6} md={4} xl={2}>
          <StatCard icon={<SecurityIcon />} label="Review Queue" value={stats?.pendingReviewCount ?? 0} color="info" />
        </Grid>
        <Grid item xs={12} sm={6} md={4} xl={2}>
          <StatCard icon={<BlockIcon />} label="Active Blocklist" value={stats?.activeBlocklistCount ?? 0} color="error" />
        </Grid>
      </Grid>

      {/* System Health */}
      {health && (
        <Card sx={{ mb: 3.5, p: 3, borderRadius: 3 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2.5 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              <Box
                sx={{
                  width: 38, height: 38, borderRadius: 2,
                  bgcolor: 'rgba(16, 185, 129, 0.12)', color: '#10b981',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}
              >
                <MonitorHeartIcon />
              </Box>
              <Box>
                <Typography variant="h6" fontWeight={800} color="text.primary">
                  Microservices & Infrastructure Health
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  Last verified: {fmtDate(health.timestamp)}
                </Typography>
              </Box>
            </Box>
            <StatusChip value={health.status} />
          </Box>

          <Grid container spacing={2}>
            {Object.entries(health)
              .filter(([k]) => !['status', 'timestamp'].includes(k))
              .map(([k, v]) => (
                <Grid item xs={12} sm={6} md={4} lg={3} key={k}>
                  <Box
                    sx={{
                      p: 2,
                      borderRadius: 2.5,
                      bgcolor: 'action.hover',
                      border: '1px solid',
                      borderColor: 'divider',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1.5,
                    }}
                  >
                    <Box sx={{ color: 'primary.main' }}>
                      {serviceIcons[k] || <CheckCircleIcon sx={{ fontSize: 20 }} />}
                    </Box>
                    <Box sx={{ overflow: 'hidden', flexGrow: 1 }}>
                      <Typography variant="caption" color="text.secondary" fontWeight={600} display="block" noWrap>
                        {k.replace(/([A-Z])/g, ' $1').trim()}
                      </Typography>
                      <Typography variant="body2" fontWeight={700} color="text.primary" noWrap>
                        {v}
                      </Typography>
                    </Box>
                    <StatusChip value={String(v).includes('UP') || String(v).includes('ACTIVE') || String(v).includes('CONNECTED') ? 'UP' : 'HEALTHY'} />
                  </Box>
                </Grid>
              ))}
          </Grid>
        </Card>
      )}

      {/* Recent Admin Audit Activity */}
      <Card sx={{ p: 3, borderRadius: 3 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2 }}>
          <Box
            sx={{
              width: 38, height: 38, borderRadius: 2,
              bgcolor: 'rgba(99, 102, 241, 0.12)', color: 'primary.main',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}
          >
            <HistoryIcon />
          </Box>
          <Box>
            <Typography variant="h6" fontWeight={800} color="text.primary">
              Recent Administrative Actions
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Real-time audit log stream from Redis
            </Typography>
          </Box>
        </Box>

        {audits.length === 0 ? (
          <Box sx={{ py: 4, textAlign: 'center' }}>
            <Typography color="text.secondary">No administrative actions recorded yet.</Typography>
          </Box>
        ) : (
          <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Action</TableCell>
                  <TableCell>Target Entity</TableCell>
                  <TableCell>Details</TableCell>
                  <TableCell>Timestamp</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {audits.slice(0, 10).map((a, i) => (
                  <TableRow key={i} hover>
                    <TableCell>
                      <Chip label={a.action} size="small" color="primary" variant="outlined" sx={{ fontWeight: 700 }} />
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600, color: 'text.primary' }}>
                      {a.target}
                    </TableCell>
                    <TableCell sx={{ color: 'text.primary' }}>{a.details}</TableCell>
                    <TableCell sx={{ color: 'text.secondary', fontSize: '0.8125rem' }}>
                      {fmtDate(a.timestamp)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Card>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 1: Users
// ══════════════════════════════════════════════════════════════════════════════
function UsersSection() {
  const [users, setUsers]     = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch]   = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminUsers(); setUsers(d || []); }
    catch { toast.error('Failed to load users'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const filtered = users.filter(u =>
    [u.name, u.email, u.upiId, u.role].some(f => (f || '').toLowerCase().includes(search.toLowerCase()))
  );

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 2, mb: 2.5, alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <TextField
          size="small"
          placeholder="Filter by name, email, UPI ID…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon sx={{ color: 'text.secondary', fontSize: 20 }} />
              </InputAdornment>
            ),
          }}
          sx={{ width: { xs: '100%', sm: 340 } }}
        />
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
          Refresh Directory
        </Button>
      </Box>

      {loading ? <LinearProgress /> : (
        <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2.5 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>User</TableCell>
                <TableCell>Email Address</TableCell>
                <TableCell>UPI ID</TableCell>
                <TableCell>Access Role</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map((u) => (
                <TableRow key={u.id} hover>
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                      <Avatar sx={{ width: 32, height: 32, fontSize: 13, fontWeight: 700, bgcolor: 'primary.main', color: '#fff' }}>
                        {(u.name || '?')[0].toUpperCase()}
                      </Avatar>
                      <Box>
                        <Typography variant="body2" fontWeight={700} color="text.primary">
                          {u.name}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          ID: #{u.id}
                        </Typography>
                      </Box>
                    </Box>
                  </TableCell>
                  <TableCell sx={{ color: 'text.primary', fontWeight: 500 }}>{u.email}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600, color: 'primary.main' }}>
                    {u.upiId}
                  </TableCell>
                  <TableCell><StatusChip value={u.role} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>
        Showing {filtered.length} of {users.length} registered accounts
      </Typography>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 2: Bank Accounts
// ══════════════════════════════════════════════════════════════════════════════
function BankAccountsSection() {
  const [accounts, setAccounts]     = useState([]);
  const [loading, setLoading]       = useState(true);
  const [adjustDlg, setAdjustDlg]   = useState(null);
  const [adjustForm, setAdjustForm] = useState({ amount: '', type: 'CREDIT', reason: '' });

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminBankAccounts(); setAccounts(d || []); }
    catch { toast.error('Failed to load bank accounts'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const toggleStatus = async (acc) => {
    const newStatus = acc.accountStatus === 'FROZEN' ? 'ACTIVE' : 'FROZEN';
    try {
      await updateBankAccountStatus(acc.accountNumber, newStatus);
      toast.success(`Account ${acc.accountNumber} set to ${newStatus}`);
      load();
    } catch { toast.error('Failed to update status'); }
  };

  const submitAdjust = async () => {
    if (!adjustForm.amount || isNaN(adjustForm.amount) || parseFloat(adjustForm.amount) <= 0) {
      toast.error('Enter a valid amount');
      return;
    }
    try {
      await adjustBankAccountBalance({
        accountNumber: adjustDlg.accountNumber,
        amount: parseFloat(adjustForm.amount),
        type: adjustForm.type,
        reason: adjustForm.reason || 'Admin manual balance adjustment',
      });
      toast.success(`Balance adjusted: ₹${fmt(adjustForm.amount)} (${adjustForm.type})`);
      setAdjustDlg(null);
      load();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Adjustment failed');
    }
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2.5 }}>
        <Typography variant="subtitle1" fontWeight={700} color="text.primary">
          Core Banking Ledger & Accounts
        </Typography>
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
          Refresh Accounts
        </Button>
      </Box>

      {loading ? <LinearProgress /> : (
        <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2.5 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Account Number</TableCell>
                <TableCell>Owner Account</TableCell>
                <TableCell>UPI ID</TableCell>
                <TableCell>Bank</TableCell>
                <TableCell align="right">Current Balance</TableCell>
                <TableCell>Status</TableCell>
                <TableCell align="center">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {accounts.map((acc) => (
                <TableRow key={acc.accountNumber} hover>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 700, color: 'text.primary' }}>
                    {acc.accountNumber}
                  </TableCell>
                  <TableCell sx={{ color: 'text.primary' }}>{acc.userId}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600, color: 'primary.main' }}>
                    {acc.upiId}
                  </TableCell>
                  <TableCell sx={{ color: 'text.secondary' }}>{acc.bankName}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 800, fontSize: '0.9375rem', color: 'text.primary' }}>
                    ₹ {fmt(acc.balance)}
                  </TableCell>
                  <TableCell><StatusChip value={acc.accountStatus || 'ACTIVE'} /></TableCell>
                  <TableCell align="center">
                    <Tooltip title={acc.accountStatus === 'FROZEN' ? 'Unfreeze Account' : 'Freeze Account'}>
                      <IconButton
                        size="small"
                        onClick={() => toggleStatus(acc)}
                        sx={{
                          color: acc.accountStatus === 'FROZEN' ? 'success.main' : 'warning.main',
                          mr: 1,
                        }}
                      >
                        {acc.accountStatus === 'FROZEN' ? <LockOpenIcon fontSize="small" /> : <LockIcon fontSize="small" />}
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="Adjust Balance">
                      <IconButton
                        size="small"
                        color="primary"
                        onClick={() => {
                          setAdjustDlg(acc);
                          setAdjustForm({ amount: '', type: 'CREDIT', reason: '' });
                        }}
                      >
                        <SavingsIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* Adjust Balance Dialog */}
      <Dialog open={!!adjustDlg} onClose={() => setAdjustDlg(null)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontWeight: 800 }}>Adjust Account Balance</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, pt: 2 }}>
          <Box sx={{ p: 2, borderRadius: 2, bgcolor: 'action.hover', border: '1px solid', borderColor: 'divider' }}>
            <Typography variant="caption" color="text.secondary" display="block">Account Number</Typography>
            <Typography variant="body2" fontWeight={700} sx={{ fontFamily: 'monospace' }}>{adjustDlg?.accountNumber}</Typography>
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>Current Balance</Typography>
            <Typography variant="h6" fontWeight={800} color="text.primary">₹ {fmt(adjustDlg?.balance)}</Typography>
          </Box>

          <FormControl size="small" fullWidth>
            <InputLabel>Operation Type</InputLabel>
            <Select
              value={adjustForm.type}
              label="Operation Type"
              onChange={(e) => setAdjustForm(f => ({ ...f, type: e.target.value }))}
            >
              <MenuItem value="CREDIT">Credit (+) Add funds</MenuItem>
              <MenuItem value="DEBIT">Debit (−) Subtract funds</MenuItem>
            </Select>
          </FormControl>

          <TextField
            label="Adjustment Amount (₹)"
            type="number"
            size="small"
            value={adjustForm.amount}
            onChange={(e) => setAdjustForm(f => ({ ...f, amount: e.target.value }))}
            inputProps={{ min: 0.01, step: 'any' }}
          />

          <TextField
            label="Administrative Reason"
            size="small"
            value={adjustForm.reason}
            multiline
            rows={2}
            onChange={(e) => setAdjustForm(f => ({ ...f, reason: e.target.value }))}
            placeholder="e.g. Compensation for dispute #1092"
          />
        </DialogContent>
        <DialogActions sx={{ p: 2.5 }}>
          <Button onClick={() => setAdjustDlg(null)}>Cancel</Button>
          <Button
            variant="contained"
            onClick={submitAdjust}
            color={adjustForm.type === 'DEBIT' ? 'error' : 'primary'}
          >
            Confirm {adjustForm.type} of ₹{adjustForm.amount || '0'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 3: Bank Transactions Ledger
// ══════════════════════════════════════════════════════════════════════════════
function BankTransactionsSection() {
  const [txs, setTxs]         = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch]   = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminBankTransactions(); setTxs(d || []); }
    catch { toast.error('Failed to load transactions'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const filtered = txs.filter(t => (t.transactionId || '').toLowerCase().includes(search.toLowerCase()));

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 2, mb: 2.5, alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <TextField
          size="small"
          placeholder="Search by transaction ID…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon sx={{ color: 'text.secondary', fontSize: 20 }} />
              </InputAdornment>
            ),
          }}
          sx={{ width: { xs: '100%', sm: 340 } }}
        />
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
          Refresh Ledger
        </Button>
      </Box>

      {loading ? <LinearProgress /> : (
        <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2.5 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Transaction ID</TableCell>
                <TableCell>Operation</TableCell>
                <TableCell align="right">Amount</TableCell>
                <TableCell align="right">Resulting Balance</TableCell>
                <TableCell>Timestamp</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map((tx, i) => (
                <TableRow key={i} hover>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600, color: 'text.primary' }}>
                    {tx.transactionId}
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={tx.type}
                      size="small"
                      color={tx.type === 'DEBIT' ? 'error' : tx.type === 'CREDIT' ? 'success' : 'info'}
                      variant="outlined"
                      sx={{ fontWeight: 700 }}
                    />
                  </TableCell>
                  <TableCell
                    align="right"
                    sx={{
                      fontWeight: 800,
                      color: tx.type === 'DEBIT' ? 'error.main' : 'success.main',
                      fontSize: '0.9375rem',
                    }}
                  >
                    {tx.type === 'DEBIT' ? '− ' : '+ '}₹ {fmt(tx.amount)}
                  </TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600, color: 'text.primary' }}>
                    ₹ {fmt(tx.balanceAfter)}
                  </TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.8125rem' }}>
                    {fmtDate(tx.appliedAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>
        Displaying {filtered.length} of {txs.length} recent ledger transactions
      </Typography>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 4: Fraud Events
// ══════════════════════════════════════════════════════════════════════════════
function FraudEventsSection() {
  const [events, setEvents]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter]   = useState('ALL');

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminFraudTransactions(); setEvents(d || []); }
    catch { toast.error('Failed to load fraud events'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const filtered = events.filter(e => filter === 'ALL' || (e.status || '').toUpperCase() === filter);

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 1.5, mb: 2.5, alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <Box sx={{ display: 'flex', gap: 1 }}>
          {['ALL', 'FRAUD', 'REVIEW', 'SAFE'].map(f => (
            <Chip
              key={f}
              label={f}
              onClick={() => setFilter(f)}
              variant={filter === f ? 'filled' : 'outlined'}
              color={f === 'FRAUD' ? 'error' : f === 'REVIEW' ? 'warning' : f === 'SAFE' ? 'success' : 'primary'}
              sx={{ fontWeight: 700 }}
              clickable
            />
          ))}
        </Box>
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
          Refresh Events
        </Button>
      </Box>

      {loading ? <LinearProgress /> : (
        <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2.5 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Transaction ID</TableCell>
                <TableCell>User</TableCell>
                <TableCell align="right">Amount</TableCell>
                <TableCell>Verdict</TableCell>
                <TableCell>AI Confidence</TableCell>
                <TableCell>Detection Layer</TableCell>
                <TableCell>Explanation / Reason</TableCell>
                <TableCell>Analyzed At</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map((e, i) => (
                <TableRow key={i} hover>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600, color: 'text.primary' }}>
                    {e.transactionId}
                  </TableCell>
                  <TableCell sx={{ color: 'text.primary' }}>{e.userId}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 800, color: 'text.primary' }}>
                    ₹ {fmt(e.amount)}
                  </TableCell>
                  <TableCell><StatusChip value={e.status} /></TableCell>
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                      <LinearProgress
                        variant="determinate"
                        value={Math.min(100, Math.max(0, (e.confidenceScore || 0) * 100))}
                        color={e.status === 'FRAUD' ? 'error' : e.status === 'REVIEW' ? 'warning' : 'success'}
                        sx={{ width: 64, height: 6, borderRadius: 3 }}
                      />
                      <Typography variant="body2" fontWeight={700} color="text.primary">
                        {((e.confidenceScore || 0) * 100).toFixed(0)}%
                      </Typography>
                    </Box>
                  </TableCell>
                  <TableCell>
                    <Chip label={e.detectionLayer || '—'} size="small" variant="outlined" sx={{ fontWeight: 600 }} />
                  </TableCell>
                  <TableCell sx={{ maxWidth: 280, color: 'text.secondary', fontSize: '0.8125rem' }}>
                    {e.reason}
                  </TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.8125rem' }}>
                    {fmtDate(e.analyzedAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>
        Showing {filtered.length} of {events.length} fraud evaluations
      </Typography>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 5: Review Queue
// ══════════════════════════════════════════════════════════════════════════════
function ReviewQueueSection() {
  const [queue, setQueue]       = useState([]);
  const [loading, setLoading]   = useState(true);
  const [deciding, setDeciding] = useState({});

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminReviewQueue(); setQueue(d || []); }
    catch { toast.error('Failed to load review queue'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const decide = async (txId, decision) => {
    setDeciding(d => ({ ...d, [txId]: true }));
    try {
      await submitAdminReview(txId, decision);
      toast.success(`Transaction ${decision === 'APPROVE' ? 'Approved & Processed' : 'Rejected & Blocked'}`);
      load();
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Decision failed');
    } finally {
      setDeciding(d => ({ ...d, [txId]: false }));
    }
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2.5 }}>
        <Alert severity="warning" sx={{ flex: 1, mr: 2, py: 0.5 }}>
          Transactions held for administrative decisioning. Approving executes payment execution; rejecting permanently blocks it.
        </Alert>
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
          Refresh Queue
        </Button>
      </Box>

      {loading ? <LinearProgress /> : queue.length === 0 ? (
        <Card sx={{ p: 6, textAlign: 'center', borderRadius: 3 }}>
          <CheckCircleIcon sx={{ fontSize: 56, color: 'success.main', mb: 1.5 }} />
          <Typography variant="h6" fontWeight={800} color="text.primary">
            Review Queue is Completely Clean
          </Typography>
          <Typography color="text.secondary">
            No transactions currently require human intervention.
          </Typography>
        </Card>
      ) : (
        <Grid container spacing={2.5}>
          {queue.map((tx) => (
            <Grid item xs={12} md={6} key={tx.transactionId}>
              <Card sx={{ p: 3, borderRadius: 3, borderLeft: '5px solid', borderColor: 'warning.main' }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 2 }}>
                  <Box>
                    <Typography variant="caption" color="text.secondary" fontWeight={600} display="block">
                      TRANSACTION ID
                    </Typography>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 700, color: 'text.primary' }}>
                      {tx.transactionId}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      Submitted: {fmtDate(tx.submittedAt)}
                    </Typography>
                  </Box>
                  <Typography variant="h4" fontWeight={900} color="warning.main">
                    ₹ {fmt(tx.amount)}
                  </Typography>
                </Box>

                <Divider sx={{ my: 1.5 }} />

                <Grid container spacing={1.5} mb={2}>
                  {[
                    ['Payee UPI', tx.payeeUpiId],
                    ['Payment Mode', tx.paymentMode],
                    ['Location', tx.location],
                    ['Device', tx.device],
                    ['Category', tx.merchantCategory],
                  ].map(([k, v]) => v ? (
                    <Grid item xs={6} key={k}>
                      <Typography variant="caption" color="text.secondary" display="block">{k}</Typography>
                      <Typography variant="body2" fontWeight={600} color="text.primary">{v}</Typography>
                    </Grid>
                  ) : null)}
                </Grid>

                <Box sx={{ p: 2, borderRadius: 2, bgcolor: 'action.hover', border: '1px solid', borderColor: 'divider', mb: 2.5 }}>
                  <Typography variant="caption" color="text.secondary" fontWeight={700} display="block">
                    FRAUD DETECTION ENGINE SIGNAL:
                  </Typography>
                  <Typography variant="body2" color="text.primary" sx={{ mt: 0.5, mb: 1 }}>
                    {tx.reason}
                  </Typography>
                  <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
                    <Chip
                      label={`${((tx.confidenceScore || 0) * 100).toFixed(0)}% AI Risk Score`}
                      size="small"
                      color="warning"
                      sx={{ fontWeight: 700 }}
                    />
                    <Chip label={tx.detectionLayer} size="small" variant="outlined" sx={{ fontWeight: 600 }} />
                  </Box>
                </Box>

                <Box sx={{ display: 'flex', gap: 2 }}>
                  <Button
                    variant="contained"
                    color="success"
                    fullWidth
                    startIcon={deciding[tx.transactionId] ? <CircularProgress size={16} color="inherit" /> : <CheckCircleIcon />}
                    disabled={!!deciding[tx.transactionId]}
                    onClick={() => decide(tx.transactionId, 'APPROVE')}
                    sx={{ fontWeight: 700 }}
                  >
                    Approve Payment
                  </Button>
                  <Button
                    variant="contained"
                    color="error"
                    fullWidth
                    startIcon={deciding[tx.transactionId] ? <CircularProgress size={16} color="inherit" /> : <CancelIcon />}
                    disabled={!!deciding[tx.transactionId]}
                    onClick={() => decide(tx.transactionId, 'REJECT')}
                    sx={{ fontWeight: 700 }}
                  >
                    Reject & Block
                  </Button>
                </Box>
              </Card>
            </Grid>
          ))}
        </Grid>
      )}
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 6: Blocklist
// ══════════════════════════════════════════════════════════════════════════════
function BlocklistSection() {
  const [list, setList]       = useState([]);
  const [loading, setLoading] = useState(true);
  const [addDlg, setAddDlg]   = useState(false);
  const [form, setForm]       = useState({ item: '', type: 'UPI_ID', reason: '' });

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminBlocklist(); setList(d || []); }
    catch { toast.error('Failed to load blocklist'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleAdd = async () => {
    if (!form.item.trim()) { toast.error('Item is required'); return; }
    try {
      await addToBlocklist(form);
      toast.success(`${form.item} added to blacklist`);
      setAddDlg(false);
      setForm({ item: '', type: 'UPI_ID', reason: '' });
      load();
    } catch { toast.error('Failed to add to blocklist'); }
  };

  const handleRemove = async (item) => {
    try {
      await removeFromBlocklist(item);
      toast.success(`${item} removed from blacklist`);
      load();
    } catch { toast.error('Failed to remove'); }
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 2, mb: 2.5, alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <Alert severity="error" sx={{ flex: 1, py: 0.5 }}>
          Entities on this blacklist are <strong>immediately rejected</strong> by the fraud engine at Layer 0 prior to rule processing.
        </Alert>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button size="small" startIcon={<AddCircleIcon />} variant="contained" color="error" onClick={() => setAddDlg(true)}>
            Add to Blacklist
          </Button>
          <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
            Refresh
          </Button>
        </Box>
      </Box>

      {loading ? <LinearProgress /> : list.length === 0 ? (
        <Card sx={{ p: 6, textAlign: 'center', borderRadius: 3 }}>
          <CheckCircleIcon sx={{ fontSize: 56, color: 'success.main', mb: 1.5 }} />
          <Typography variant="h6" fontWeight={800} color="text.primary">
            Blacklist is Empty
          </Typography>
          <Typography color="text.secondary">
            No entities are currently flagged for immediate denial.
          </Typography>
        </Card>
      ) : (
        <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2.5 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Blacklisted Entity</TableCell>
                <TableCell>Entity Type</TableCell>
                <TableCell>Justification / Reason</TableCell>
                <TableCell>Added On</TableCell>
                <TableCell align="center">Remove</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {list.map((entry, i) => (
                <TableRow key={i} hover>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 700, color: 'error.main' }}>
                    {entry.item}
                  </TableCell>
                  <TableCell>
                    <Chip label={entry.type} size="small" color="error" variant="outlined" sx={{ fontWeight: 700 }} />
                  </TableCell>
                  <TableCell sx={{ color: 'text.primary' }}>{entry.reason}</TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.8125rem' }}>
                    {fmtDate(entry.addedAt)}
                  </TableCell>
                  <TableCell align="center">
                    <Tooltip title="Remove from blacklist">
                      <IconButton size="small" color="error" onClick={() => handleRemove(entry.item)}>
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* Add Dialog */}
      <Dialog open={addDlg} onClose={() => setAddDlg(false)} maxWidth="xs" fullWidth>
        <DialogTitle sx={{ fontWeight: 800 }}>Add Entity to Blacklist</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, pt: 2 }}>
          <FormControl size="small" fullWidth>
            <InputLabel>Entity Type</InputLabel>
            <Select
              value={form.type}
              label="Entity Type"
              onChange={(e) => setForm(f => ({ ...f, type: e.target.value }))}
            >
              <MenuItem value="UPI_ID">UPI ID (e.g. scammer@upi)</MenuItem>
              <MenuItem value="USER_ID">User Email / Account</MenuItem>
              <MenuItem value="LOCATION">City / Location</MenuItem>
              <MenuItem value="DEVICE">Device Fingerprint</MenuItem>
            </Select>
          </FormControl>
          <TextField
            label="Entity Identifier"
            size="small"
            value={form.item}
            onChange={(e) => setForm(f => ({ ...f, item: e.target.value }))}
            placeholder={form.type === 'UPI_ID' ? 'e.g. fraudster@upi' : 'Enter identifier'}
          />
          <TextField
            label="Reason for Blacklisting"
            size="small"
            value={form.reason}
            multiline
            rows={2}
            onChange={(e) => setForm(f => ({ ...f, reason: e.target.value }))}
            placeholder="e.g. Associated with confirmed phishing ring"
          />
        </DialogContent>
        <DialogActions sx={{ p: 2.5 }}>
          <Button onClick={() => setAddDlg(false)}>Cancel</Button>
          <Button variant="contained" color="error" onClick={handleAdd}>
            Enforce Blacklist
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 7: Fraud Rules
// ══════════════════════════════════════════════════════════════════════════════
function RulesSection() {
  const [, setRules]          = useState({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving]   = useState(false);
  const [edited, setEdited]   = useState({});

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminFraudRules(); setRules(d || {}); setEdited(d || {}); }
    catch { toast.error('Failed to load rules'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setSaving(true);
    try {
      await updateAdminFraudRules(edited);
      toast.success('Fraud rules updated — changes active in real time');
      setRules(edited);
    } catch { toast.error('Failed to save rules'); }
    finally { setSaving(false); }
  };

  const ruleDescriptions = {
    highAmountThreshold: 'Transaction amount (₹) threshold triggering mandatory REVIEW',
    highRiskScoreCutoff: 'AI Risk Score above this value results in immediate FRAUD block (0.00 – 1.00)',
    reviewScoreCutoff:   'AI Risk Score above this value routes transaction to REVIEW queue (0.00 – 1.00)',
    maxTxPerMinute:      'Max transactions allowed per minute per user before velocity FRAUD',
    ruleEngineEnabled:   'Enable/disable Rule Engine (Layer 1 deterministic rules)',
    mlModelEnabled:      'Enable/disable ML History Analyser (Layer 2 contextual model)',
    geminiAiEnabled:     'Enable/disable Google Gemini AI (Layer 3 cognitive reasoning)',
  };

  if (loading) return <LinearProgress />;

  return (
    <Box>
      <Alert severity="info" sx={{ mb: 3 }}>
        Parameters configured here are synchronized via Redis and evaluated dynamically on every transaction without restarting any services.
      </Alert>

      <Grid container spacing={2.5}>
        {Object.entries(edited).map(([key, val]) => (
          <Grid item xs={12} sm={6} key={key}>
            <Card sx={{ p: 2.5, borderRadius: 3, height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
              <Box>
                <Typography variant="subtitle2" fontWeight={800} color="text.primary" sx={{ fontFamily: 'monospace', mb: 0.5 }}>
                  {key}
                </Typography>
                <Typography variant="caption" color="text.secondary" display="block" mb={2}>
                  {ruleDescriptions[key] || 'Dynamic fraud policy configuration parameter'}
                </Typography>
              </Box>

              {typeof val === 'boolean' ? (
                <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <Chip
                    label={val ? 'ENABLED' : 'DISABLED'}
                    color={val ? 'success' : 'error'}
                    sx={{ fontWeight: 800 }}
                  />
                  <Button
                    size="small"
                    variant="outlined"
                    color={val ? 'error' : 'primary'}
                    onClick={() => setEdited(e => ({ ...e, [key]: !val }))}
                  >
                    {val ? 'Deactivate Layer' : 'Activate Layer'}
                  </Button>
                </Box>
              ) : (
                <TextField
                  type="number"
                  size="small"
                  fullWidth
                  value={val}
                  onChange={(e) => setEdited(r => ({ ...r, [key]: parseFloat(e.target.value) || 0 }))}
                  inputProps={{ step: key.toLowerCase().includes('cutoff') ? 0.01 : 1 }}
                />
              )}
            </Card>
          </Grid>
        ))}
      </Grid>

      <Box sx={{ display: 'flex', gap: 2, mt: 3 }}>
        <Button
          variant="contained"
          onClick={save}
          disabled={saving}
          startIcon={saving ? <CircularProgress size={16} color="inherit" /> : null}
          sx={{ fontWeight: 700, px: 3 }}
        >
          {saving ? 'Updating Pipeline…' : 'Save & Publish Rules'}
        </Button>
        <Button variant="outlined" onClick={load}>
          Discard Changes
        </Button>
      </Box>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  SECTION 8: Audit Log
// ══════════════════════════════════════════════════════════════════════════════
function AuditLogSection() {
  const [logs, setLogs]       = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try { const d = await getAdminAuditLogs(); setLogs(d || []); }
    catch { toast.error('Failed to load audit logs'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const actionColor = (a) => {
    if (a?.includes('BLOCK')) return 'error';
    if (a?.includes('REVIEW')) return 'warning';
    if (a?.includes('RULES')) return 'info';
    return 'primary';
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2.5 }}>
        <Typography variant="subtitle1" fontWeight={700} color="text.primary">
          Administrator Action Audit Trail
        </Typography>
        <Button size="small" startIcon={<RefreshIcon />} onClick={load} variant="outlined">
          Refresh Log
        </Button>
      </Box>

      {loading ? <LinearProgress /> : logs.length === 0 ? (
        <Card sx={{ p: 6, textAlign: 'center', borderRadius: 3 }}>
          <HistoryIcon sx={{ fontSize: 56, color: 'text.secondary', mb: 1.5 }} />
          <Typography variant="h6" fontWeight={800} color="text.primary">
            No Administrative Actions Recorded
          </Typography>
        </Card>
      ) : (
        <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2.5 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>#</TableCell>
                <TableCell>Action Category</TableCell>
                <TableCell>Target Identifier</TableCell>
                <TableCell>Details / Outcome</TableCell>
                <TableCell>Timestamp</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {logs.map((log, i) => (
                <TableRow key={i} hover>
                  <TableCell sx={{ color: 'text.secondary', fontWeight: 600 }}>{i + 1}</TableCell>
                  <TableCell>
                    <Chip label={log.action} size="small" color={actionColor(log.action)} sx={{ fontWeight: 700 }} />
                  </TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 700, color: 'text.primary' }}>
                    {log.target}
                  </TableCell>
                  <TableCell sx={{ color: 'text.primary' }}>{log.details}</TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.8125rem' }}>
                    {fmtDate(log.timestamp)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>
        Preserving the last 50 administrative actions recorded in Redis
      </Typography>
    </Box>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
//  ROOT: AdminPanel
// ══════════════════════════════════════════════════════════════════════════════
const TABS = [
  { label: 'Overview',       icon: <BarChartIcon sx={{ fontSize: 18 }} /> },
  { label: 'Users',          icon: <PeopleIcon sx={{ fontSize: 18 }} /> },
  { label: 'Bank Accounts',  icon: <AccountBalanceIcon sx={{ fontSize: 18 }} /> },
  { label: 'Transactions',   icon: <ReceiptLongIcon sx={{ fontSize: 18 }} /> },
  { label: 'Fraud Events',   icon: <SecurityIcon sx={{ fontSize: 18 }} /> },
  { label: 'Review Queue',   icon: <WarningAmberIcon sx={{ fontSize: 18 }} /> },
  { label: 'Blacklist',      icon: <BlockIcon sx={{ fontSize: 18 }} /> },
  { label: 'Fraud Rules',    icon: <RuleIcon sx={{ fontSize: 18 }} /> },
  { label: 'Audit Log',      icon: <HistoryIcon sx={{ fontSize: 18 }} /> },
];

export default function AdminPanel() {
  const { user }   = useAuth();
  const [tab, setTab] = useState(0);

  return (
    <Box>
      {/* Header Banner */}
      <Box sx={{ mb: 3 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2, mb: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
            <Box
              sx={{
                width: 46, height: 46, borderRadius: 2.5,
                background: 'linear-gradient(135deg, #6366f1 0%, #3b82f6 100%)',
                color: '#ffffff',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                boxShadow: '0 4px 12px rgba(99, 102, 241, 0.35)',
              }}
            >
              <AdminPanelSettingsIcon sx={{ fontSize: 28 }} />
            </Box>
            <Box>
              <Typography variant="h5" fontWeight={900} color="text.primary" lineHeight={1.1}>
                Admin Operations Portal
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
                Signed in as <strong style={{ color: 'inherit' }}>{user?.name}</strong> ({user?.email}) · Full System Authority
              </Typography>
            </Box>
          </Box>

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Box
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                px: 1.5,
                py: 0.5,
                borderRadius: 2,
                bgcolor: 'rgba(16, 185, 129, 0.12)',
                color: '#10b981',
                fontSize: '0.75rem',
                fontWeight: 800,
                border: '1px solid rgba(16, 185, 129, 0.25)',
              }}
            >
              <Box component="span" sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#10b981' }} />
              LIVE TELEMETRY
            </Box>
            <Chip label="ROLE_ADMIN" color="primary" size="small" sx={{ fontWeight: 800 }} />
          </Box>
        </Box>

        <Alert severity="success" sx={{ borderRadius: 2 }}>
          🔒 <strong>Exclusive Access Zone:</strong> Administrative commands are strictly restricted to <strong>karthik@gmail.com</strong>.
          Every action is immutably signed to the audit log.
        </Alert>
      </Box>

      {/* Modern Navigation Tabs */}
      <Paper elevation={0} sx={{ borderRadius: 3, border: '1px solid', borderColor: 'divider', overflow: 'hidden' }}>
        <Tabs
          value={tab}
          onChange={(_, v) => setTab(v)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{
            bgcolor: 'action.hover',
            borderBottom: 1,
            borderColor: 'divider',
            minHeight: 52,
            '& .MuiTab-root': {
              minHeight: 52,
              fontSize: '0.875rem',
              fontWeight: 600,
              color: 'text.secondary',
              textTransform: 'none',
              px: 2.5,
              '&.Mui-selected': {
                color: 'primary.main',
                fontWeight: 800,
              },
            },
            '& .MuiTabs-indicator': {
              height: 3,
              borderRadius: '3px 3px 0 0',
              bgcolor: 'primary.main',
            },
          }}
        >
          {TABS.map((t, i) => (
            <Tab key={i} label={t.label} icon={t.icon} iconPosition="start" />
          ))}
        </Tabs>

        <Box sx={{ p: { xs: 2, sm: 3 } }}>
          <TabPanel value={tab} index={0}><OverviewSection /></TabPanel>
          <TabPanel value={tab} index={1}><UsersSection /></TabPanel>
          <TabPanel value={tab} index={2}><BankAccountsSection /></TabPanel>
          <TabPanel value={tab} index={3}><BankTransactionsSection /></TabPanel>
          <TabPanel value={tab} index={4}><FraudEventsSection /></TabPanel>
          <TabPanel value={tab} index={5}><ReviewQueueSection /></TabPanel>
          <TabPanel value={tab} index={6}><BlocklistSection /></TabPanel>
          <TabPanel value={tab} index={7}><RulesSection /></TabPanel>
          <TabPanel value={tab} index={8}><AuditLogSection /></TabPanel>
        </Box>
      </Paper>
    </Box>
  );
}
