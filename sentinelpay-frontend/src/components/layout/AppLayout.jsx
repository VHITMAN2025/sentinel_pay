import React, { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Box, useMediaQuery, useTheme } from '@mui/material';
import Navbar  from './Navbar';
import Sidebar from './Sidebar';

const DRAWER_WIDTH = 260;

export default function AppLayout() {
  const theme   = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));
  const [mobileOpen, setMobileOpen] = useState(false);

  const handleDrawerToggle = () => setMobileOpen((prev) => !prev);

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      {/* Top navigation bar */}
      <Navbar drawerWidth={DRAWER_WIDTH} onMenuClick={handleDrawerToggle} />

      {/* Sidebar navigation */}
      <Sidebar
        drawerWidth={DRAWER_WIDTH}
        mobileOpen={mobileOpen}
        onClose={handleDrawerToggle}
        isMobile={isMobile}
      />

      {/* Main content area */}
      <Box
        component="main"
        sx={{
          flexGrow: 1,
          minWidth: 0,
          pt: '64px', // Navbar height
          width: { md: `calc(100% - ${DRAWER_WIDTH}px)` },
          minHeight: '100vh',
          overflowX: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <Box sx={{ p: { xs: 2, sm: 2.5, md: 3 }, width: '100%', boxSizing: 'border-box', flexGrow: 1 }}>
          <Outlet />
        </Box>
      </Box>
    </Box>
  );
}
