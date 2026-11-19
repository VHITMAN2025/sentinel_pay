import React, { useEffect, useState, useCallback } from 'react';
import {
  Card, CardContent, Box, Typography, IconButton,
  Tooltip, CircularProgress, Divider, Chip, Button,
  Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, Alert, InputAdornment,
} from '@mui/material';
import RefreshIcon              from '@mui/icons-material/Refresh';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import VisibilityIcon           from '@mui/icons-material/Visibility';
import VisibilityOffIcon        from '@mui/icons-material/VisibilityOff';
import LockIcon                 from '@mui/icons-material/Lock';
import LockOpenIcon             from '@mui/icons-material/LockOpen';
import KeyIcon                  from '@mui/icons-material/Key';
import toast                    from 'react-hot-toast';

import { getBalance, verifyUpiPin } from '../../api/bankApi';
import { formatCurrency } from '../../utils/formatters';
import { useAuth } from '../../context/AuthContext';
import { useAppState } from '../../context/AppStateContext';

export default function BalanceCard() {
  const { user } = useAuth();
  const { balanceRefreshKey } = useAppState();

  const [balance, setBalance]             = useState(null);
  const [loading, setLoading]             = useState(false);
  const [isRevealed, setIsRevealed]       = useState(false);
  const [pinDialogOpen, setPinDialogOpen] = useState(false);
  const [upiPinInput, setUpiPinInput]     = useState('212006');
  const [showPin, setShowPin]             = useState(false);
  const [verifying, setVerifying]         = useState(false);
  const [pinError, setPinError]           = useState('');

  const fetchBalanceWithPin = useCallback(async (pin, silent = false) => {
    if (!silent) setLoading(true);
    try {
      const data = await getBalance(pin);
      setBalance(data);
      setIsRevealed(true);
      return true;
    } catch (err) {
      return false;
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  // Poll quietly if balance is already revealed
  useEffect(() => {
    if (!isRevealed) return;
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') fetchBalanceWithPin(upiPinInput, true);
    };
    const interval = window.setInterval(refreshWhenVisible, 6000);
    window.addEventListener('focus', refreshWhenVisible);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener('focus', refreshWhenVisible);
    };
  }, [isRevealed, fetchBalanceWithPin, upiPinInput, balanceRefreshKey]);

  const handleOpenPinDialog = () => {
    setPinError('');
    setPinDialogOpen(true);
  };

  const handleVerifyAndCheck = async (e) => {
    if (e) e.preventDefault();
    if (!upiPinInput || upiPinInput.length !== 6) {
      setPinError('Please enter a valid 6-digit UPI PIN');
      return;
    }

    setVerifying(true);
    setPinError('');
    try {
      const verifyRes = await verifyUpiPin(upiPinInput);
      if (verifyRes.valid) {
        await fetchBalanceWithPin(upiPinInput);
        setPinDialogOpen(false);
        toast.success('UPI PIN verified. Balance revealed.');
      } else {
        setPinError('Incorrect UPI PIN. Default is 212006.');
      }
    } catch (err) {
      const msg = err.response?.data?.message || err.response?.data?.error || err.message || 'Incorrect UPI PIN. Please try again.';
      setPinError(msg);
      toast.error(msg);
    } finally {
      setVerifying(false);
    }
  };

  const handleHideBalance = () => {
    setIsRevealed(false);
  };

  return (
    <>
      <Card
        sx={{
          background: 'linear-gradient(135deg, #4f46e5 0%, #2563eb 50%, #1d4ed8 100%)',
          color: '#ffffff',
          borderRadius: 3.5,
          boxShadow: '0 8px 30px -4px rgba(37, 99, 235, 0.35)',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        <CardContent sx={{ p: 3 }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <AccountBalanceWalletIcon sx={{ fontSize: 28, opacity: 0.9 }} />
              <Typography variant="body1" sx={{ opacity: 0.9, fontWeight: 600 }}>
                Account Balance
              </Typography>
            </Box>

            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
              {isRevealed ? (
                <>
                  <Tooltip title="Hide Balance">
                    <IconButton size="small" sx={{ color: 'rgba(255,255,255,0.8)' }} onClick={handleHideBalance}>
                      <VisibilityOffIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title="Refresh balance">
                    <IconButton
                      size="small"
                      sx={{ color: 'rgba(255,255,255,0.8)' }}
                      onClick={() => fetchBalanceWithPin(upiPinInput)}
                      disabled={loading}
                    >
                      <RefreshIcon fontSize="small" />
                    </IconButton>
                  </Tooltip>
                </>
              ) : (
                <Tooltip title="PIN Protected">
                  <LockIcon sx={{ fontSize: 18, color: 'rgba(255,255,255,0.7)', mt: 0.5 }} />
                </Tooltip>
              )}
            </Box>
          </Box>

          <Typography variant="caption" sx={{ opacity: 0.72, display: 'block', mt: 0.5 }}>
            Balance for {user?.email || 'your signed-in account'}
          </Typography>

          <Box sx={{ mt: 2, mb: 1, minHeight: 48, display: 'flex', alignItems: 'center' }}>
            {loading ? (
              <CircularProgress size={32} sx={{ color: 'rgba(255,255,255,0.7)' }} />
            ) : isRevealed && balance ? (
              <Typography variant="h3" fontWeight={800} letterSpacing="-1px">
                {formatCurrency(balance?.balance)}
              </Typography>
            ) : (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, width: '100%', justifyContent: 'space-between' }}>
                <Typography variant="h4" fontWeight={800} letterSpacing="3px" sx={{ opacity: 0.85 }}>
                  ₹ • • • • • •
                </Typography>
                <Button
                  variant="contained"
                  size="small"
                  startIcon={<KeyIcon />}
                  onClick={handleOpenPinDialog}
                  sx={{
                    bgcolor: 'rgba(255,255,255,0.2)',
                    backdropFilter: 'blur(8px)',
                    color: '#ffffff',
                    fontWeight: 700,
                    textTransform: 'none',
                    borderRadius: 2,
                    px: 2,
                    '&:hover': { bgcolor: 'rgba(255,255,255,0.3)' },
                  }}
                >
                  Check Balance
                </Button>
              </Box>
            )}
          </Box>

          <Divider sx={{ borderColor: 'rgba(255,255,255,0.2)', my: 1.5 }} />

          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Box>
              <Typography variant="caption" sx={{ opacity: 0.7 }}>UPI ID</Typography>
              <Typography variant="body2" fontWeight={600}>{user?.upiId || '—'}</Typography>
            </Box>
            <Chip
              icon={<LockOpenIcon style={{ color: '#fff', fontSize: 14 }} />}
              label={isRevealed ? 'UNLOCKED' : 'PIN PROTECTED'}
              size="small"
              sx={{ bgcolor: 'rgba(255,255,255,0.2)', color: 'white', fontWeight: 700, fontSize: '0.7rem' }}
            />
          </Box>
        </CardContent>
      </Card>

      {/* ─── UPI PIN Check Balance Dialog ─────────────────────────────────── */}
      <Dialog
        open={pinDialogOpen}
        onClose={() => !verifying && setPinDialogOpen(false)}
        maxWidth="xs"
        fullWidth
        PaperProps={{
          sx: {
            borderRadius: 3,
            p: 1,
            bgcolor: 'background.paper',
          },
        }}
      >
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1.5, pb: 1 }}>
          <Box
            sx={{
              p: 1,
              borderRadius: 2,
              bgcolor: 'primary.main',
              color: '#ffffff',
              display: 'flex',
            }}
          >
            <KeyIcon fontSize="small" />
          </Box>
          <Box>
            <Typography variant="h6" fontWeight={700}>Enter UPI PIN</Typography>
            <Typography variant="caption" color="text.secondary">
              Required to access live bank account balance
            </Typography>
          </Box>
        </DialogTitle>

        <form onSubmit={handleVerifyAndCheck}>
          <DialogContent sx={{ pt: 1 }}>
            {pinError && (
              <Alert severity="error" sx={{ mb: 2, borderRadius: 2 }}>
                {pinError}
              </Alert>
            )}

            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              Please enter your 6-digit bank security PIN to decrypt and reveal your account balance.
            </Typography>

            <TextField
              fullWidth
              autoFocus
              label="6-Digit UPI PIN"
              type={showPin ? 'text' : 'password'}
              value={upiPinInput}
              onChange={(e) => setUpiPinInput(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
              placeholder="212006"
              inputProps={{
                maxLength: 6,
                inputMode: 'numeric',
                pattern: '[0-9]*',
                style: { letterSpacing: '4px', fontSize: '1.2rem', textAlign: 'center' },
              }}
              InputProps={{
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton size="small" onClick={() => setShowPin(!showPin)} edge="end">
                      {showPin ? <VisibilityOffIcon fontSize="small" /> : <VisibilityIcon fontSize="small" />}
                    </IconButton>
                  </InputAdornment>
                ),
              }}
              helperText="Default UPI PIN is 212006 (editable in Profile Settings)"
              sx={{ mb: 1 }}
            />
          </DialogContent>

          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button
              onClick={() => setPinDialogOpen(false)}
              disabled={verifying}
              sx={{ textTransform: 'none' }}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="contained"
              disabled={verifying || upiPinInput.length !== 6}
              startIcon={verifying ? <CircularProgress size={16} color="inherit" /> : <KeyIcon />}
              sx={{ textTransform: 'none', px: 3, fontWeight: 700 }}
            >
              {verifying ? 'Verifying...' : 'Check Balance'}
            </Button>
          </DialogActions>
        </form>
      </Dialog>
    </>
  );
}
