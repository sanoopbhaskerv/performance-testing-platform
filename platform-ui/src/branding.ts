import axios from 'axios';

export interface Branding {
  name: string;
  logoUrl: string;
  logoAlt: string;
  theme: 'light' | 'dark';
  colors: { primary: string; accent: string };
}

export const DEFAULT_BRANDING: Branding = {
  name: 'Performance Studio', logoUrl: '', logoAlt: 'Performance Studio logo', theme: 'dark',
  colors: { primary: '#4f46e5', accent: '#c084fc' },
};

const record = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const text = (value: unknown, fallback: string) =>
  typeof value === 'string' && value.trim() ? value.trim().slice(0, 80) : fallback;
const color = (value: unknown, fallback: string) =>
  typeof value === 'string' && /^#[\da-f]{6}$/i.test(value) ? value : fallback;

/** Only image coordinates and validated color values cross into the DOM; never HTML or CSS. */
export function normalizeBranding(value: unknown): Branding {
  const config = record(value); const colors = record(config.colors);
  const name = text(config.name, DEFAULT_BRANDING.name);
  let logoUrl = '';
  if (typeof config.logoUrl === 'string') {
    const candidate = config.logoUrl.trim();
    if (/^\/branding\/[a-z\d_./-]+$/i.test(candidate) && !candidate.split('/').includes('..')) logoUrl = candidate;
    else {
      try {
        const url = new URL(candidate);
        if (url.protocol === 'https:' && !url.username && !url.password) logoUrl = url.href;
      } catch { /* An absent or invalid logo uses the built-in icon. */ }
    }
  }
  return { name, logoUrl, logoAlt: text(config.logoAlt, `${name} logo`),
    theme: config.theme === 'light' ? 'light' : 'dark',
    colors: { primary: color(colors.primary, DEFAULT_BRANDING.colors.primary),
      accent: color(colors.accent, DEFAULT_BRANDING.colors.accent) } };
}

export async function loadBranding(): Promise<Branding> {
  const response = await axios.get<unknown>('/platform-config.json', { headers: { 'Cache-Control': 'no-cache' } });
  return normalizeBranding(response.data);
}

const channels = (hex: string) => [1, 3, 5].map(offset => parseInt(hex.slice(offset, offset + 2), 16));

/** Choose the more legible neutral foreground for the configured solid action color. */
function foreground(hex: string): string {
  const linear = channels(hex).map(channel => {
    const value = channel / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  const luminance = linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
  return (luminance + 0.05) / 0.05 > 1.05 / (luminance + 0.05) ? '#000000' : '#ffffff';
}

/** Apply the same brand to the shell and studio. Return cleanup for reloads and embedded use. */
export function applyBranding(branding: Branding): () => void {
  const root = document.documentElement;
  const previousTitle = document.title; const previousTheme = root.dataset.theme;
  const variables: Record<string, string> = {
    '--brand-primary': branding.colors.primary, '--brand-secondary': branding.colors.accent,
    '--brand-accent-rgb': channels(branding.colors.accent).join(' '),
    '--brand-gradient': `linear-gradient(135deg, ${branding.colors.primary}, ${branding.colors.accent})`,
    '--pf-brand-500': branding.colors.accent, '--pf-brand-600': branding.colors.primary,
    '--pf-brand-rgb': channels(branding.colors.primary).join(' '),
    '--pf-text-on-action': foreground(branding.colors.primary),
  };
  const previous = Object.keys(variables).map(key => [key, root.style.getPropertyValue(key)]);
  Object.entries(variables).forEach(([key, value]) => root.style.setProperty(key, value));
  root.dataset.theme = branding.theme; document.title = branding.name;
  return () => {
    previous.forEach(([key, value]) => value ? root.style.setProperty(key, value) : root.style.removeProperty(key));
    if (previousTheme === undefined) delete root.dataset.theme; else root.dataset.theme = previousTheme;
    document.title = previousTitle;
  };
}
