/**
 * ThemeContext.jsx — Accessible, Modern Dark / Light Design System (V4)
 *
 * Provides:
 *  - High-contrast, WCAG AAA compliant text and palette colors
 *  - Rich obsidian dark mode (not muddy pitch-black) with clean subtle borders
 *  - Clean slate light mode with crisp typography
 *  - Persisted user preference in localStorage under 'sentinelpay_theme'
 */

import React, {
  createContext, useContext, useState, useMemo, useCallback,
} from 'react';
import { createTheme } from '@mui/material/styles';

const THEME_KEY = 'sentinelpay_theme';

const ThemeContext = createContext(null);

function buildTheme(mode) {
  const isDark = mode === 'dark';

  return createTheme({
    palette: {
      mode,
      primary: {
        main:         isDark ? '#6366f1' : '#2563eb', // Vibrant indigo in dark, royal blue in light
        light:        isDark ? '#818cf8' : '#3b82f6',
        dark:         isDark ? '#4f46e5' : '#1d4ed8',
        contrastText: '#ffffff',
      },
      secondary: {
        main:         isDark ? '#38bdf8' : '#0284c7', // Sky cyan
        light:        isDark ? '#7dd3fc' : '#38bdf8',
        dark:         isDark ? '#0284c7' : '#0369a1',
        contrastText: '#ffffff',
      },
      background: {
        default: isDark ? '#0a0e17' : '#f8fafc',
        paper:   isDark ? '#111827' : '#ffffff',
      },
      text: {
        primary:   isDark ? '#f8fafc' : '#0f172a',
        secondary: isDark ? '#94a3b8' : '#475569',
        disabled:  isDark ? '#64748b' : '#94a3b8',
      },
      error: {
        main:         isDark ? '#ef4444' : '#dc2626',
        light:        isDark ? '#f87171' : '#ef4444',
        dark:         isDark ? '#b91c1c' : '#991b1b',
        contrastText: '#ffffff',
      },
      warning: {
        main:         isDark ? '#f59e0b' : '#d97706',
        light:        isDark ? '#fbbf24' : '#f59e0b',
        dark:         isDark ? '#b45309' : '#92400e',
        contrastText: '#ffffff',
      },
      success: {
        main:         isDark ? '#10b981' : '#16a34a',
        light:        isDark ? '#34d399' : '#22c55e',
        dark:         isDark ? '#047857' : '#15803d',
        contrastText: '#ffffff',
      },
      info: {
        main:         isDark ? '#38bdf8' : '#0284c7',
        light:        isDark ? '#7dd3fc' : '#38bdf8',
        dark:         isDark ? '#0369a1' : '#075985',
        contrastText: '#ffffff',
      },
      divider: isDark ? 'rgba(255, 255, 255, 0.08)' : 'rgba(0, 0, 0, 0.08)',
      action: {
        hover:    isDark ? 'rgba(255, 255, 255, 0.05)' : 'rgba(0, 0, 0, 0.04)',
        selected: isDark ? 'rgba(99, 102, 241, 0.12)' : 'rgba(37, 99, 235, 0.08)',
      },
    },
    typography: {
      fontFamily: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      h3: { fontWeight: 800, letterSpacing: '-0.02em' },
      h4: { fontWeight: 800, letterSpacing: '-0.02em' },
      h5: { fontWeight: 700, letterSpacing: '-0.01em' },
      h6: { fontWeight: 700, letterSpacing: '-0.01em' },
      subtitle1: { fontWeight: 600 },
      subtitle2: { fontWeight: 600 },
      body1: { fontSize: '0.9375rem', lineHeight: 1.6 },
      body2: { fontSize: '0.875rem', lineHeight: 1.5 },
      caption: { fontSize: '0.75rem', fontWeight: 500 },
      button: { textTransform: 'none', fontWeight: 600 },
    },
    shape: { borderRadius: 10 },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            scrollbarColor: isDark ? '#374151 #111827' : '#cbd5e1 #f8fafc',
            '&::-webkit-scrollbar, & *::-webkit-scrollbar': {
              width: 8,
              height: 8,
            },
            '&::-webkit-scrollbar-thumb, & *::-webkit-scrollbar-thumb': {
              borderRadius: 8,
              backgroundColor: isDark ? '#374151' : '#cbd5e1',
            },
          },
        },
      },
      MuiCard: {
        styleOverrides: {
          root: {
            borderRadius: 14,
            backgroundImage: 'none',
            backgroundColor: isDark ? '#111827' : '#ffffff',
            border: isDark ? '1px solid rgba(255, 255, 255, 0.08)' : '1px solid #e2e8f0',
            boxShadow: isDark
              ? '0 4px 20px -2px rgba(0, 0, 0, 0.4)'
              : '0 1px 3px rgba(0, 0, 0, 0.05), 0 1px 2px rgba(0, 0, 0, 0.03)',
            transition: 'border-color 0.2s ease, box-shadow 0.2s ease',
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          root: {
            backgroundImage: 'none',
            backgroundColor: isDark ? '#111827' : '#ffffff',
          },
        },
      },
      MuiAppBar: {
        styleOverrides: {
          root: {
            backgroundImage: 'none',
            backgroundColor: isDark ? 'rgba(17, 24, 39, 0.85)' : 'rgba(255, 255, 255, 0.85)',
            backdropFilter: 'blur(12px)',
            borderBottom: isDark ? '1px solid rgba(255, 255, 255, 0.08)' : '1px solid #e2e8f0',
            color: isDark ? '#f8fafc' : '#0f172a',
          },
        },
      },
      MuiDrawer: {
        styleOverrides: {
          paper: {
            backgroundColor: isDark ? '#0d131f' : '#ffffff',
            borderRight: isDark ? '1px solid rgba(255, 255, 255, 0.08)' : '1px solid #e2e8f0',
          },
        },
      },
      MuiButton: {
        styleOverrides: {
          root: {
            textTransform: 'none',
            fontWeight: 600,
            borderRadius: 8,
            padding: '7px 16px',
          },
          containedPrimary: {
            background: isDark
              ? 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)'
              : 'linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%)',
            boxShadow: isDark
              ? '0 2px 10px rgba(99, 102, 241, 0.35)'
              : '0 2px 8px rgba(37, 99, 235, 0.3)',
            '&:hover': {
              background: isDark
                ? 'linear-gradient(135deg, #818cf8 0%, #6366f1 100%)'
                : 'linear-gradient(135deg, #3b82f6 0%, #2563eb 100%)',
            },
          },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: {
            borderBottom: isDark ? '1px solid rgba(255, 255, 255, 0.07)' : '1px solid #f1f5f9',
            padding: '12px 16px',
            color: isDark ? '#f8fafc' : '#0f172a',
          },
          head: {
            backgroundColor: isDark ? '#1a2234' : '#f1f5f9',
            color: isDark ? '#94a3b8' : '#475569',
            fontWeight: 700,
            fontSize: '0.8125rem',
            textTransform: 'uppercase',
            letterSpacing: '0.04em',
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: {
            fontWeight: 600,
            borderRadius: 6,
          },
        },
      },
      MuiTextField: {
        defaultProps: { size: 'small' },
        styleOverrides: {
          root: {
            '& .MuiOutlinedInput-root': {
              borderRadius: 8,
              backgroundColor: isDark ? 'rgba(255, 255, 255, 0.03)' : '#ffffff',
            },
          },
        },
      },
      MuiAlert: {
        styleOverrides: {
          root: {
            borderRadius: 10,
            fontWeight: 500,
          },
          standardSuccess: {
            backgroundColor: isDark ? 'rgba(16, 185, 129, 0.12)' : '#ecfdf5',
            color: isDark ? '#34d399' : '#065f46',
            border: isDark ? '1px solid rgba(16, 185, 129, 0.25)' : '1px solid #a7f3d0',
          },
          standardInfo: {
            backgroundColor: isDark ? 'rgba(56, 189, 248, 0.12)' : '#f0f9ff',
            color: isDark ? '#38bdf8' : '#0369a1',
            border: isDark ? '1px solid rgba(56, 189, 248, 0.25)' : '1px solid #bae6fd',
          },
          standardWarning: {
            backgroundColor: isDark ? 'rgba(245, 158, 11, 0.12)' : '#fffbeb',
            color: isDark ? '#fbbf24' : '#92400e',
            border: isDark ? '1px solid rgba(245, 158, 11, 0.25)' : '1px solid #fde68a',
          },
          standardError: {
            backgroundColor: isDark ? 'rgba(239, 68, 68, 0.12)' : '#fef2f2',
            color: isDark ? '#f87171' : '#991b1b',
            border: isDark ? '1px solid rgba(239, 68, 68, 0.25)' : '1px solid #fecaca',
          },
        },
      },
    },
  });
}

export function ThemeProvider({ children }) {
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  const stored      = localStorage.getItem(THEME_KEY);

  const [mode, setMode] = useState(
    stored === 'dark' || stored === 'light' ? stored : (prefersDark ? 'dark' : 'light')
  );

  const muiTheme = useMemo(() => buildTheme(mode), [mode]);

  const toggleTheme = useCallback(() => {
    setMode((prev) => {
      const next = prev === 'light' ? 'dark' : 'light';
      localStorage.setItem(THEME_KEY, next);
      return next;
    });
  }, []);

  return (
    <ThemeContext.Provider value={{ mode, toggleTheme, muiTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used inside <ThemeProvider>');
  return ctx;
}
