import React from 'react';
import { Navigate, Outlet, useNavigate } from 'react-router-dom';
import { Box, Typography, Button, Paper } from '@mui/material';
import LockIcon from '@mui/icons-material/Lock';
import { useAuth } from '../context/AuthContext';

/**
 * AdminRoute — wraps routes that require the ADMIN role.
 *
 * Exclusively restricted to karthik@gmail.com.
 * Unauthenticated users   → /login
 * Authenticated non-admin → 403 inline error screen
 */
export default function AdminRoute() {
  const { isAuthenticated, isAdmin, user, loading } = useAuth();
  const navigate = useNavigate();

  if (loading) return null;

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (!isAdmin) {
    return (
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '80vh',
          p: 3,
        }}
      >
        <Paper
          elevation={3}
          sx={{
            p: 5,
            maxWidth: 480,
            textAlign: 'center',
            borderRadius: 3,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 2,
            border: '1px solid',
            borderColor: 'error.light',
          }}
        >
          <Box
            sx={{
              width: 80,
              height: 80,
              borderRadius: '50%',
              bgcolor: 'error.light',
              color: 'error.dark',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <LockIcon sx={{ fontSize: 44 }} />
          </Box>
          <Typography variant="h5" fontWeight={800} color="error.main">
            Access Restricted
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Administrative access is strictly restricted to administrator <strong>karthik@gmail.com</strong>.
          </Typography>
          <Typography variant="caption" sx={{ bgcolor: 'action.hover', px: 2, py: 1, borderRadius: 1 }}>
            Signed in as: <strong>{user?.email || 'Unknown'}</strong>
          </Typography>
          <Box sx={{ display: 'flex', gap: 2, mt: 1 }}>
            <Button variant="contained" onClick={() => navigate('/dashboard')}>
              Back to Dashboard
            </Button>
            <Button variant="outlined" color="secondary" onClick={() => navigate('/profile')}>
              My Profile
            </Button>
          </Box>
        </Paper>
      </Box>
    );
  }

  return <Outlet />;
}
