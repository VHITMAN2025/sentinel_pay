import React, { useState, useEffect } from 'react';
import {
  Box, Typography, Card, CardContent, Grid, Avatar,
  TextField, Divider, Chip, Button,
  Alert, List, ListItem, ListItemIcon, ListItemText,
  Dialog, DialogTitle, DialogContent, DialogActions,
  IconButton, InputAdornment, CircularProgress,
} from '@mui/material';
import AccountBalanceIcon   from '@mui/icons-material/AccountBalance';
import SecurityIcon         from '@mui/icons-material/Security';
import CheckCircleIcon      from '@mui/icons-material/CheckCircle';
import KeyIcon              from '@mui/icons-material/Key';
import LockResetIcon        from '@mui/icons-material/LockReset';
import VisibilityIcon       from '@mui/icons-material/Visibility';
import VisibilityOffIcon    from '@mui/icons-material/VisibilityOff';
import toast                from 'react-hot-toast';

import { useAuth }          from '../context/AuthContext';
import { getBalance, changeUpiPin } from '../api/bankApi';
import { formatCurrency }   from '../utils/formatters';

export default function Profile() {
  const { user } = useAuth();

  const [balance, setBalance]       = useState(null);
  const [balLoading, setBalLoading] = useState(true);

  // Change UPI PIN state
  const [pinDialogOpen, setPinDialogOpen] = useState(false);
  const [currentPin, setCurrentPin]       = useState('');
  const [newPin, setNewPin]               = useState('');
  const [confirmPin, setConfirmPin]       = useState('');
  const [showCurrentPin, setShowCurrentPin] = useState(false);
  const [showNewPin, setShowNewPin]         = useState(false);
  const [pinSaving, setPinSaving]         = useState(false);
  const [pinError, setPinError]           = useState('');

  useEffect(() => {
    getBalance()
      .then(setBalance)
      .catch(() => {})
      .finally(() => setBalLoading(false));
  }, []);

  const initials = user?.name
    ? user.name.split(' ').map((w) => w[0]).join('').toUpperCase().slice(0, 2)
    : '?';

  const handleOpenPinDialog = () => {
    setCurrentPin('');
    setNewPin('');
    setConfirmPin('');
    setPinError('');
    setPinDialogOpen(true);
  };

  const handleChangePinSubmit = async (e) => {
    if (e) e.preventDefault();
    if (!currentPin || currentPin.length !== 6) {
      setPinError('Current UPI PIN must be 6 digits (Default: 212006)');
      return;
    }
    if (!newPin || !/^[0-9]{6}$/.test(newPin)) {
      setPinError('New UPI PIN must be exactly 6 digits');
      return;
    }
    if (newPin !== confirmPin) {
      setPinError('New UPI PIN and Confirm PIN do not match');
      return;
    }

    setPinSaving(true);
    setPinError('');
    try {
      await changeUpiPin(currentPin, newPin);
      toast.success('UPI PIN changed successfully! Your new PIN is active.');
      setPinDialogOpen(false);
    } catch (err) {
      setPinError(err.response?.data?.message || 'Failed to update UPI PIN. Verify current PIN.');
    } finally {
      setPinSaving(false);
    }
  };

  return (
    <Box>
      <Typography variant="h5" fontWeight={700} mb={0.5}>Profile & Security</Typography>
      <Typography variant="body2" color="text.secondary" mb={3}>
        Manage your UPI identity, PIN security settings, and platform authentication.
      </Typography>

      <Grid container spacing={3}>
        {/* User card */}
        <Grid item xs={12} md={4}>
          <Card sx={{ mb: 3 }}>
            <CardContent sx={{ p: 3, textAlign: 'center' }}>
              <Avatar
                sx={{
                  width: 88,
                  height: 88,
                  bgcolor: 'primary.main',
                  fontSize: '2rem',
                  fontWeight: 800,
                  mx: 'auto',
                  mb: 2,
                }}
              >
                {initials}
              </Avatar>

              <Typography variant="h6" fontWeight={700}>{user?.name}</Typography>
              <Typography variant="body2" color="text.secondary" mb={1}>{user?.email}</Typography>

              <Chip
                label={user?.upiId || '—'}
                variant="outlined"
                color="primary"
                sx={{ fontWeight: 600, mb: 2 }}
              />

              <Divider sx={{ my: 2 }} />

              <List dense disablePadding>
                <ListItem disableGutters>
                  <ListItemIcon sx={{ minWidth: 36 }}>
                    <AccountBalanceIcon fontSize="small" color="primary" />
                  </ListItemIcon>
                  <ListItemText
                    primary="Balance"
                    secondary={balLoading ? '…' : formatCurrency(balance?.balance)}
                    primaryTypographyProps={{ variant: 'caption', color: 'text.secondary' }}
                    secondaryTypographyProps={{ variant: 'body2', fontWeight: 700, color: 'text.primary' }}
                  />
                </ListItem>

                <ListItem disableGutters>
                  <ListItemIcon sx={{ minWidth: 36 }}>
                    <SecurityIcon fontSize="small" color="success" />
                  </ListItemIcon>
                  <ListItemText
                    primary="Account Status"
                    secondary={balance?.accountStatus || 'ACTIVE'}
                    primaryTypographyProps={{ variant: 'caption', color: 'text.secondary' }}
                    secondaryTypographyProps={{ variant: 'body2', fontWeight: 700, color: 'success.main' }}
                  />
                </ListItem>
              </List>
            </CardContent>
          </Card>
        </Grid>

        {/* Account details and PIN */}
        <Grid item xs={12} md={8}>
          <Card sx={{ mb: 3 }}>
            <CardContent sx={{ p: 3 }}>
              <Typography variant="h6" fontWeight={700} mb={2}>Account Details</Typography>

              <Grid container spacing={2}>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    label="Full Name"
                    value={user?.name || ''}
                    InputProps={{ readOnly: true }}
                    variant="outlined"
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    label="Email Address"
                    value={user?.email || ''}
                    InputProps={{ readOnly: true }}
                    variant="outlined"
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    label="UPI ID"
                    value={user?.upiId || balance?.upiId || '—'}
                    InputProps={{ readOnly: true }}
                    variant="outlined"
                  />
                </Grid>
                <Grid item xs={12} sm={6}>
                  <TextField
                    fullWidth
                    label="Account Balance"
                    value={balLoading ? 'Loading…' : formatCurrency(balance?.balance)}
                    InputProps={{ readOnly: true }}
                    variant="outlined"
                  />
                </Grid>
              </Grid>

              <Alert severity="info" sx={{ mt: 2, borderRadius: 2 }}>
                Profile identity is managed by the bank and auth service.
              </Alert>
            </CardContent>
          </Card>

          {/* ── UPI PIN Settings Card ── */}
          <Card sx={{ mb: 3, border: '1px solid', borderColor: 'primary.light', bgcolor: 'background.paper' }}>
            <CardContent sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <Box sx={{ p: 1, borderRadius: 2, bgcolor: 'primary.main', color: '#fff', display: 'flex' }}>
                    <KeyIcon fontSize="small" />
                  </Box>
                  <Box>
                    <Typography variant="h6" fontWeight={700}>UPI PIN Protection</Typography>
                    <Typography variant="caption" color="text.secondary">
                      Secures account balance inquiry and payment execution
                    </Typography>
                  </Box>
                </Box>
                <Chip label="ACTIVE (6 DIGITS)" color="primary" size="small" sx={{ fontWeight: 700 }} />
              </Box>

              <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5 }}>
                Your 6-digit UPI PIN authorizes every outbound payment and authenticates balance checks.
                The system-wide default PIN is <strong>212006</strong>. You can change it below anytime.
              </Typography>

              <Button
                variant="outlined"
                color="primary"
                startIcon={<LockResetIcon />}
                onClick={handleOpenPinDialog}
                sx={{ textTransform: 'none', fontWeight: 700, borderRadius: 2, px: 2.5 }}
              >
                Change UPI PIN
              </Button>
            </CardContent>
          </Card>

          {/* Security features */}
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Typography variant="h6" fontWeight={700} mb={2}>Active Security Defenses</Typography>

              {[
                {
                  title:   'UPI PIN Authorization',
                  desc:    '6-digit PIN verification before debiting funds or revealing sensitive balance.',
                  enabled: true,
                },
                {
                  title:   'Fuzzy Brand Typosquatting Detection (Levenshtein)',
                  desc:    'Blocks phishing handles impersonating brands like Amazon, PhonePe, SBI, Paytm in real time.',
                  enabled: true,
                },
                {
                  title:   'Payee Cold-Start Surge & Mule Account Detection',
                  desc:    'Identifies rapid velocity bursts and high-value payments to newly activated recipient accounts.',
                  enabled: true,
                },
                {
                  title:   'JWT Gateway Authentication & Rate Limiting',
                  desc:    'Strict Bearer token validation and 5-submission limit per 60s.',
                  enabled: true,
                },
                {
                  title:   '3-Layer Real-time AI Scoring',
                  desc:    'Deterministic Rules → Redis Velocity History → Google Gemini AI scoring pipeline.',
                  enabled: true,
                },
              ].map(({ title, desc }) => (
                <Box
                  key={title}
                  sx={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 2,
                    mb: 2,
                    p: 1.5,
                    borderRadius: 2,
                    bgcolor: 'action.hover',
                  }}
                >
                  <CheckCircleIcon color="success" sx={{ mt: 0.3, flexShrink: 0 }} />
                  <Box>
                    <Typography variant="body2" fontWeight={700}>{title}</Typography>
                    <Typography variant="caption" color="text.secondary">{desc}</Typography>
                  </Box>
                  <Chip
                    label="Active"
                    size="small"
                    color="success"
                    sx={{ ml: 'auto', flexShrink: 0, fontWeight: 700 }}
                  />
                </Box>
              ))}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* ── Change UPI PIN Dialog ── */}
      <Dialog
        open={pinDialogOpen}
        onClose={() => !pinSaving && setPinDialogOpen(false)}
        maxWidth="xs"
        fullWidth
        PaperProps={{ sx: { borderRadius: 3, p: 1 } }}
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1.5, pb: 1 }}>
          <Box sx={{ p: 1, borderRadius: 2, bgcolor: 'primary.main', color: '#fff', display: 'flex' }}>
            <LockResetIcon fontSize="small" />
          </Box>
          <Box>
            <Typography variant="h6" fontWeight={700}>Change UPI PIN</Typography>
            <Typography variant="caption" color="text.secondary">
              Update your 6-digit transaction PIN
            </Typography>
          </Box>
        </DialogTitle>

        <form onSubmit={handleChangePinSubmit}>
          <DialogContent sx={{ pt: 1 }}>
            {pinError && (
              <Alert severity="error" sx={{ mb: 2, borderRadius: 2 }}>
                {pinError}
              </Alert>
            )}

            <TextField
              fullWidth
              label="Current UPI PIN"
              type={showCurrentPin ? 'text' : 'password'}
              value={currentPin}
              onChange={(e) => setCurrentPin(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
              placeholder="Default: 212006"
              inputProps={{ maxLength: 6, inputMode: 'numeric', style: { letterSpacing: '4px', textAlign: 'center' } }}
              InputProps={{
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton size="small" onClick={() => setShowCurrentPin(!showCurrentPin)} edge="end">
                      {showCurrentPin ? <VisibilityOffIcon fontSize="small" /> : <VisibilityIcon fontSize="small" />}
                    </IconButton>
                  </InputAdornment>
                ),
              }}
              helperText="Default PIN is 212006"
              sx={{ mb: 2 }}
            />

            <TextField
              fullWidth
              label="New 6-Digit UPI PIN"
              type={showNewPin ? 'text' : 'password'}
              value={newPin}
              onChange={(e) => setNewPin(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
              inputProps={{ maxLength: 6, inputMode: 'numeric', style: { letterSpacing: '4px', textAlign: 'center' } }}
              InputProps={{
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton size="small" onClick={() => setShowNewPin(!showNewPin)} edge="end">
                      {showNewPin ? <VisibilityOffIcon fontSize="small" /> : <VisibilityIcon fontSize="small" />}
                    </IconButton>
                  </InputAdornment>
                ),
              }}
              sx={{ mb: 2 }}
            />

            <TextField
              fullWidth
              label="Confirm New UPI PIN"
              type="password"
              value={confirmPin}
              onChange={(e) => setConfirmPin(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
              inputProps={{ maxLength: 6, inputMode: 'numeric', style: { letterSpacing: '4px', textAlign: 'center' } }}
              sx={{ mb: 1 }}
            />
          </DialogContent>

          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button onClick={() => setPinDialogOpen(false)} disabled={pinSaving} sx={{ textTransform: 'none' }}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="contained"
              disabled={pinSaving || newPin.length !== 6 || confirmPin.length !== 6 || currentPin.length !== 6}
              startIcon={pinSaving ? <CircularProgress size={16} color="inherit" /> : <LockResetIcon />}
              sx={{ textTransform: 'none', px: 3, fontWeight: 700 }}
            >
              {pinSaving ? 'Updating...' : 'Update PIN'}
            </Button>
          </DialogActions>
        </form>
      </Dialog>
    </Box>
  );
}
