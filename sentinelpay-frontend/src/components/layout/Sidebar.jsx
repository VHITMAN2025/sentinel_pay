/**
 * Sidebar.jsx — Upgraded Navigation Sidebar (V4)
 *
 * Modern high-contrast navigation with responsive mobile drawer,
 * clear role badges, and accessible typography.
 */

import React, { useMemo } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  Drawer, Box, List, ListItem, ListItemButton, ListItemIcon,
  ListItemText, Typography, Divider, Avatar, Badge,
  IconButton, Tooltip, Chip,
} from '@mui/material';
import DashboardIcon    from '@mui/icons-material/Dashboard';
import PaymentIcon      from '@mui/icons-material/Payment';
import ShieldIcon       from '@mui/icons-material/Shield';
import PersonIcon       from '@mui/icons-material/Person';
import SecurityIcon     from '@mui/icons-material/Security';
import AdminPanelIcon   from '@mui/icons-material/AdminPanelSettings';
import BarChartIcon     from '@mui/icons-material/BarChart';
import LogoutIcon       from '@mui/icons-material/Logout';
import { useAuth }      from '../../context/AuthContext';
import { useAppState }  from '../../context/AppStateContext';

function DrawerContent({ onClose }) {
  const { user, isAdmin, logout } = useAuth();
  const { unreadAlerts, clearAppState } = useAppState();
  const navigate  = useNavigate();
  const location  = useLocation();

  const navItems = useMemo(() => [
    { label: 'Dashboard',    path: '/dashboard',    icon: <DashboardIcon /> },
    { label: 'Transactions', path: '/transactions', icon: <PaymentIcon /> },
    {
      label: 'Fraud Alerts',
      path:  '/fraud-alerts',
      icon:  (
        <Badge badgeContent={unreadAlerts} color="error" max={9}>
          <ShieldIcon />
        </Badge>
      ),
    },
    { label: 'Analytics', path: '/analytics', icon: <BarChartIcon /> },
    { label: 'Profile',   path: '/profile',   icon: <PersonIcon /> },
    ...(isAdmin ? [{ label: 'Admin Portal', path: '/admin', icon: <AdminPanelIcon />, adminOnly: true }] : []),
  ], [unreadAlerts, isAdmin]);

  const initials = user?.name
    ? user.name.split(' ').map((w) => w[0]).join('').toUpperCase().slice(0, 2)
    : '?';

  const handleNav = (path) => {
    navigate(path);
    if (onClose) onClose();
  };

  const handleLogout = () => {
    clearAppState();
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* Brand Header */}
      <Box sx={{ p: 2.5, display: 'flex', alignItems: 'center', gap: 1.5 }}>
        <Box
          sx={{
            width: 40, height: 40, borderRadius: 2,
            background: 'linear-gradient(135deg, #6366f1 0%, #3b82f6 100%)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: '#ffffff', boxShadow: '0 2px 8px rgba(99, 102, 241, 0.4)',
          }}
        >
          <SecurityIcon sx={{ fontSize: 24 }} />
        </Box>
        <Box>
          <Typography variant="h6" fontWeight={800} color="text.primary" lineHeight={1.1}>
            SentinelPay
          </Typography>
          <Typography variant="caption" color="text.secondary" fontWeight={600}>
            AI Fraud Defense
          </Typography>
        </Box>
      </Box>

      <Divider />

      {/* User Info Bar */}
      <Box sx={{ px: 2, py: 2, display: 'flex', alignItems: 'center', gap: 1.5 }}>
        <Avatar
          sx={{
            background: 'linear-gradient(135deg, #3b82f6 0%, #2563eb 100%)',
            color: '#ffffff',
            width: 42,
            height: 42,
            fontSize: '0.9375rem',
            fontWeight: 700,
          }}
        >
          {initials}
        </Avatar>
        <Box sx={{ overflow: 'hidden', flexGrow: 1 }}>
          <Typography variant="body2" fontWeight={700} color="text.primary" noWrap>
            {user?.name}
          </Typography>
          <Typography variant="caption" color="text.secondary" display="block" noWrap>
            {user?.upiId || user?.email}
          </Typography>
          {isAdmin && (
            <Chip
              label="ADMIN"
              size="small"
              color="primary"
              sx={{ height: 18, fontSize: '0.625rem', fontWeight: 800, mt: 0.5 }}
            />
          )}
        </Box>
        <Tooltip title="Sign out">
          <IconButton size="small" onClick={handleLogout} sx={{ color: 'text.secondary', '&:hover': { color: 'error.main' } }}>
            <LogoutIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Divider />

      {/* Navigation List */}
      <List sx={{ px: 1.5, py: 1.5, flexGrow: 1 }}>
        {navItems.map(({ label, path, icon, adminOnly }) => {
          const active = location.pathname === path;
          return (
            <ListItem key={path} disablePadding sx={{ mb: 0.75 }}>
              <ListItemButton
                onClick={() => handleNav(path)}
                selected={active}
                sx={{
                  borderRadius: 2,
                  py: 1,
                  px: 1.5,
                  transition: 'all 0.15s ease',
                  ...(adminOnly && !active && {
                    bgcolor: 'rgba(99, 102, 241, 0.08)',
                    border: '1px dashed rgba(99, 102, 241, 0.3)',
                    '&:hover': { bgcolor: 'rgba(99, 102, 241, 0.15)' },
                  }),
                  '&.Mui-selected': {
                    background: 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)',
                    color: '#ffffff',
                    boxShadow: '0 2px 8px rgba(99, 102, 241, 0.35)',
                    '& .MuiListItemIcon-root': { color: '#ffffff' },
                    '&:hover': {
                      background: 'linear-gradient(135deg, #818cf8 0%, #6366f1 100%)',
                    },
                  },
                }}
              >
                <ListItemIcon
                  sx={{
                    minWidth: 38,
                    color: active ? '#ffffff' : adminOnly ? 'primary.main' : 'text.secondary',
                  }}
                >
                  {icon}
                </ListItemIcon>
                <ListItemText
                  primary={label}
                  primaryTypographyProps={{
                    fontWeight: active ? 700 : adminOnly ? 700 : 500,
                    fontSize: '0.875rem',
                    color: active ? '#ffffff' : adminOnly ? 'primary.main' : 'text.primary',
                  }}
                />
                {adminOnly && (
                  <Chip
                    label="PRO"
                    size="small"
                    sx={{
                      height: 18,
                      fontSize: '0.625rem',
                      fontWeight: 800,
                      bgcolor: active ? 'rgba(255,255,255,0.25)' : 'primary.main',
                      color: '#ffffff',
                    }}
                  />
                )}
              </ListItemButton>
            </ListItem>
          );
        })}
      </List>

      <Divider />
      <Box sx={{ p: 2, textAlign: 'center' }}>
        <Typography variant="caption" color="text.secondary" fontWeight={500}>
          SentinelPay v2.0 Enterprise
        </Typography>
      </Box>
    </Box>
  );
}

export default function Sidebar({ drawerWidth, mobileOpen, onClose }) {
  return (
    <Box component="nav" sx={{ width: { md: drawerWidth }, flexShrink: { md: 0 } }}>
      {/* Mobile Drawer */}
      <Drawer
        variant="temporary"
        open={mobileOpen}
        onClose={onClose}
        ModalProps={{ keepMounted: true }}
        sx={{
          display: { xs: 'block', md: 'none' },
          '& .MuiDrawer-paper': { width: drawerWidth, boxSizing: 'border-box' },
        }}
      >
        <DrawerContent onClose={onClose} />
      </Drawer>

      {/* Desktop Permanent Drawer */}
      <Drawer
        variant="permanent"
        sx={{
          display: { xs: 'none', md: 'block' },
          '& .MuiDrawer-paper': { width: drawerWidth, boxSizing: 'border-box' },
        }}
        open
      >
        <DrawerContent />
      </Drawer>
    </Box>
  );
}
