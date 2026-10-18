import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import axios from 'axios';
import { afterEach, expect, it, vi } from 'vitest';
import BrandHeader from './BrandHeader';
import { applyBranding, DEFAULT_BRANDING, normalizeBranding } from './branding';

afterEach(() => vi.restoreAllMocks());

it('loads a company name, local logo, light theme and colors without rebuilding the UI', async () => {
  const get = vi.spyOn(axios, 'get').mockResolvedValue({ data: {
    name: 'Acme Performance Lab', logoUrl: '/branding/logo.svg', logoAlt: 'Acme',
    theme: 'light', colors: { primary: '#0f766e', accent: '#0891b2' },
  } });
  render(<BrandHeader />);
  expect(await screen.findByText('Acme Performance Lab')).toBeInTheDocument();
  expect(screen.getByRole('img', { name: 'Acme' })).toHaveAttribute('src', '/branding/logo.svg');
  await waitFor(() => expect(document.title).toBe('Acme Performance Lab'));
  expect(document.documentElement.dataset.theme).toBe('light');
  expect(document.documentElement.style.getPropertyValue('--pf-brand-rgb')).toBe('15 118 110');
  expect(get).toHaveBeenCalledWith('/platform-config.json', { headers: { 'Cache-Control': 'no-cache' } });
  fireEvent.error(screen.getByRole('img', { name: 'Acme' }));
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  expect(screen.getByText('Acme Performance Lab')).toBeInTheDocument();
});

it('uses defaults when the deployment has no branding file', async () => {
  vi.spyOn(axios, 'get').mockRejectedValue(new Error('not found'));
  render(<BrandHeader />);
  expect(screen.getByText(DEFAULT_BRANDING.name)).toBeInTheDocument();
});

it.each(['javascript:alert(1)', 'data:image/svg+xml,bad', '//other.example.com/logo', '/branding/../api/private'])(
'refuses active, protocol-relative and escaping logo coordinates: %s', logoUrl => {
  expect(normalizeBranding({ logoUrl }).logoUrl).toBe('');
});

it('accepts HTTPS logos but excludes embedded credentials and arbitrary CSS', () => {
  expect(normalizeBranding({ logoUrl: 'https://cdn.example.com/logo.png' }).logoUrl).toBe('https://cdn.example.com/logo.png');
  const value = normalizeBranding({ name: '', logoUrl: 'https://user:password@example.com/logo',
    theme: 'anything', colors: { primary: 'red;display:none', accent: '#fff' } });
  expect(value.name).toBe(DEFAULT_BRANDING.name);
  expect(value.logoUrl).toBe('');
  expect(value.colors).toEqual(DEFAULT_BRANDING.colors);
  expect(value.theme).toBe('dark');
});

it('keeps action text legible on bright colors and restores the embedding document', () => {
  const title = document.title;
  const restore = applyBranding(normalizeBranding({ name: 'Bright', colors: { primary: '#ffff00' } }));
  expect(document.documentElement.style.getPropertyValue('--pf-text-on-action')).toBe('#000000');
  restore(); expect(document.title).toBe(title);
});
