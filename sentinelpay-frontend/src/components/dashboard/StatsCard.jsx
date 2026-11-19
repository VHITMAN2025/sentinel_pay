import React from 'react';
import { Card, CardContent, Box, Typography } from '@mui/material';

/**
 * StatsCard — Modern, responsive metric display card shown on Dashboards.
 *
 * @param {React.ReactNode} icon       - MUI icon element
 * @param {string}          title      - metric label
 * @param {string|number}   value      - primary value
 * @param {string}          [subtitle] - optional secondary label
 * @param {'primary'|'success'|'warning'|'error'|'info'} [color]
 */
export default function StatsCard({ icon, title, value, subtitle, color = 'primary' }) {
  const colorMap = {
    primary: { text: 'primary.main', bg: 'rgba(99, 102, 241, 0.12)' },
    success: { text: 'success.main', bg: 'rgba(16, 185, 129, 0.12)' },
    warning: { text: 'warning.main', bg: 'rgba(245, 158, 11, 0.12)' },
    error:   { text: 'error.main',   bg: 'rgba(239, 68, 68, 0.12)' },
    info:    { text: 'info.main',    bg: 'rgba(56, 189, 248, 0.12)' },
  };

  const scheme = colorMap[color] || colorMap.primary;

  return (
    <Card
      sx={{
        height: '100%',
        p: 0.5,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        transition: 'transform 0.15s ease, box-shadow 0.15s ease',
        '&:hover': {
          transform: 'translateY(-2px)',
        },
      }}
    >
      <CardContent sx={{ p: 2.5, '&:last-child': { pb: 2.5 } }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
          <Typography variant="body2" color="text.secondary" fontWeight={600} noWrap>
            {title}
          </Typography>
          <Box
            sx={{
              width: 42,
              height: 42,
              borderRadius: 2.5,
              bgcolor: scheme.bg,
              color: scheme.text,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
            }}
          >
            {icon}
          </Box>
        </Box>

        <Typography variant="h4" fontWeight={800} color="text.primary" sx={{ letterSpacing: '-0.02em', mb: 0.5 }}>
          {value ?? '—'}
        </Typography>

        {subtitle && (
          <Typography variant="caption" color="text.secondary" fontWeight={500} sx={{ display: 'block' }}>
            {subtitle}
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}
