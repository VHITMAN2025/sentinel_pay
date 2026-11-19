/**
 * Navbar.jsx — Top navigation bar (V4)
 *
 * Modern glassmorphism bar with adaptive dark/light styling,
 * clear typography, and accessible contrasts.
 */

import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AppBar, Toolbar, Typography, IconButton, Avatar,
  Menu, MenuItem, ListItemIcon, Divider, Box, Chip,
  Badge, Alert, Tooltip,
} from '@mui/material';
import MenuIcon          from '@mui/icons-material/Menu';
import LogoutIcon        from '@mui/icons-material/Logout';
import PersonIcon        from '@mui/icons-material/Person';
import SecurityIcon      from '@mui/icons-material/Security';
import NotificationsIcon from '@mui/icons-material/Notifications';
import AdminPanelIcon    from '@mui/icons-material/AdminPanelSettings';
import CloseIcon         from '@mui/icons-material/Close';
import DarkModeIcon      from '@mui/icons-material/DarkMode';
import LightModeIcon     from '@mui/icons-material/LightMode';
import { useAuth }       from '../../context/AuthContext';
import { useAppState }   from '../../context/AppStateContext';
import { useTheme }      from '../../context/ThemeContext';

export default function Navbar({ drawerWidth, onMenuClick }) {
  const { user, isAdmin, logout, globalError, clearGlobalError } = useAuth();
  const { unreadAlerts, clearAppState }                           = useAppState();
  const { mode, toggleTheme }                                     = useTheme();
  const navigate                                                  = useNavigate();
  const [anchorEl, setAnchorEl]                                   = useState(null);

  const handleMenuOpen  = (e) => setAnchorEl(e.currentTarget);
  const handleMenuClose = ()  => setAnchorEl(null);

  const handleProfile = () => { handleMenuClose(); navigate('/profile'); };
  const handleAdmin   = () => { handleMenuClose(); navigate('/admin'); };

  const handleLogout = () => {
    handleMenuClose();
    clearAppState();
    logout();
    navigate('/login', { replace: true });
  };

  const initials = user?.name
    ? user.name.split(' ').map((w) => w[0]).join('').toUpperCase().slice(0, 2)
    : '?';

  const isDark = mode === 'dark';

  return (
    <>
      <AppBar
        position="fixed"
        elevation={0}
        sx={{
          width: { md: `calc(100% - ${drawerWidth}px)` },
          ml:    { md: `${drawerWidth}px` },
          bgcolor: isDark ? 'rgba(17, 24, 39, 0.85)' : 'rgba(255, 255, 255, 0.85)',
          backdropFilter: 'blur(12px)',
          borderBottom: isDark ? '1px solid rgba(255, 255, 255, 0.08)' : '1px solid #e2e8f0',
          color: 'text.primary',
        }}
      >
        <Toolbar sx={{ minHeight: 64 }}>
          {/* Mobile hamburger */}
          <IconButton
            edge="start"
            onClick={onMenuClick}
            sx={{ mr: 2, display: { md: 'none' }, color: 'text.primary' }}
          >
            <MenuIcon />
          </IconButton>

          {/* Brand Shield & Title */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexGrow: 1 }}>
            <SecurityIcon sx={{ color: 'primary.main', fontSize: 28 }} />
            <Typography variant="h6" noWrap sx={{ fontWeight: 800, color: 'text.primary', letterSpacing: '-0.3px' }}>
              SentinelPay
            </Typography>
            <Typography
              variant="caption"
              sx={{
                display: { xs: 'none', sm: 'inline-block' },
                bgcolor: isDark ? 'rgba(99, 102, 241, 0.15)' : 'rgba(37, 99, 235, 0.1)',
                color: 'primary.main',
                px: 1, py: 0.25,
                borderRadius: 1,
                fontWeight: 700,
                fontSize: '0.6875rem',
                ml: 1,
              }}
            >
              REAL-TIME AI FRAUD DEFENSE
            </Typography>
          </Box>

          {/* Admin badge */}
          {isAdmin && (
            <Chip
              label="ADMIN"
              size="small"
              color="primary"
              sx={{
                mr: 1.5,
                fontWeight: 800,
                fontSize: '0.7rem',
                letterSpacing: '0.05em',
              }}
            />
          )}

          {/* UPI ID chip */}
          {user?.upiId && (
            <Chip
              label={user.upiId}
              size="small"
              variant="outlined"
              sx={{
                mr: 1.5,
                fontWeight: 600,
                fontSize: '0.75rem',
                display: { xs: 'none', sm: 'flex' },
                borderColor: isDark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.15)',
              }}
            />
          )}

          {/* Dark/Light mode toggle */}
          <Tooltip title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}>
            <IconButton
              onClick={toggleTheme}
              sx={{ mr: 0.5, color: 'text.secondary', '&:hover': { color: 'text.primary' } }}
              aria-label="toggle dark mode"
            >
              {isDark ? <LightModeIcon sx={{ color: '#fbbf24' }} /> : <DarkModeIcon />}
            </IconButton>
          </Tooltip>

          {/* Notification bell */}
          <Tooltip title="Fraud Alerts">
            <IconButton
              sx={{ mr: 1, color: 'text.secondary', '&:hover': { color: 'text.primary' } }}
              onClick={() => navigate('/fraud-alerts')}
              aria-label="fraud alerts"
            >
              <Badge badgeContent={unreadAlerts} color="error" max={9}>
                <NotificationsIcon />
              </Badge>
            </IconButton>
          </Tooltip>

          {/* Avatar dropdown */}
          <IconButton onClick={handleMenuOpen} size="small" sx={{ ml: 0.5 }}>
            <Avatar
              sx={{
                width: 38,
                height: 38,
                background: 'linear-gradient(135deg, #6366f1 0%, #3b82f6 100%)',
                color: '#ffffff',
                fontSize: '0.875rem',
                fontWeight: 700,
                border: '2px solid',
                borderColor: isDark ? 'rgba(255,255,255,0.1)' : '#ffffff',
              }}
            >
              {initials}
            </Avatar>
          </IconButton>

          <Menu
            anchorEl={anchorEl}
            open={Boolean(anchorEl)}
            onClose={handleMenuClose}
            transformOrigin={{ horizontal: 'right', vertical: 'top' }}
            anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
            PaperProps={{
              elevation: 4,
              sx: {
                mt: 1.5,
                minWidth: 230,
                borderRadius: 2.5,
                border: isDark ? '1px solid rgba(255,255,255,0.08)' : '1px solid #e2e8f0',
              },
            }}
          >
            <Box sx={{ px: 2, py: 1.5 }}>
              <Typography variant="subtitle2" fontWeight={700} color="text.primary">{user?.name}</Typography>
              <Typography variant="caption" color="text.secondary" display="block">{user?.email}</Typography>
              {isAdmin && (
                <Chip label="Administrator" size="small" color="primary" sx={{ mt: 0.75, height: 20, fontSize: '0.6875rem', fontWeight: 700 }} />
              )}
            </Box>
            <Divider />
            <MenuItem onClick={handleProfile}>
              <ListItemIcon><PersonIcon fontSize="small" /></ListItemIcon>
              Profile
            </MenuItem>
            {isAdmin && (
              <MenuItem onClick={handleAdmin}>
                <ListItemIcon><AdminPanelIcon fontSize="small" color="primary" /></ListItemIcon>
                Admin Portal
              </MenuItem>
            )}
            <Divider />
            <MenuItem onClick={handleLogout} sx={{ color: 'error.main' }}>
              <ListItemIcon><LogoutIcon fontSize="small" color="error" /></ListItemIcon>
              Sign Out
            </MenuItem>
          </Menu>
        </Toolbar>

        {/* Global Error Banner */}
        {globalError && (
          <Alert
            severity="error"
            action={
              <IconButton size="small" color="inherit" onClick={clearGlobalError}>
                <CloseIcon fontSize="small" />
              </IconButton>
            }
            sx={{ borderRadius: 0, py: 0.5 }}
          >
            {globalError}
          </Alert>
        )}
      </AppBar>
    </>
  );
}
