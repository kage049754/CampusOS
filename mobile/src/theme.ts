export type PaletteName = 'forest' | 'sunset' | 'monochrome' | 'ocean' | 'mint' | 'sky' | 'lavender' | 'plum' | 'rose' | 'coral' | 'amber' | 'teal';

export type Palette = {
  name: PaletteName;
  label: string;
  primary: string;
  primarySoft: string;
  accent: string;
  background: string;
  surface: string;
  surfaceAlt: string;
  text: string;
  muted: string;
  border: string;
  danger: string;
};

export const palettes: Record<PaletteName, Palette> = {
  forest: { name: 'forest', label: 'Forest', primary: '#176B3A', primarySoft: '#B8F2C8', accent: '#326B67', background: '#F4F7F2', surface: '#FFFFFF', surfaceAlt: '#E8F4EC', text: '#17251C', muted: '#65746A', border: '#D5E9DC', danger: '#B3261E' },
  sunset: { name: 'sunset', label: 'Sunset', primary: '#B3261E', primarySoft: '#FFDAD1', accent: '#705E1F', background: '#FFF8F5', surface: '#FFFFFF', surfaceAlt: '#FFF2EE', text: '#2A1C1A', muted: '#806D67', border: '#F3DED7', danger: '#B3261E' },
  monochrome: { name: 'monochrome', label: 'Monochrome', primary: '#3F4650', primarySoft: '#E0E4E9', accent: '#5D636B', background: '#F5F6F7', surface: '#FFFFFF', surfaceAlt: '#F1F3F5', text: '#202326', muted: '#6D747C', border: '#E1E5E9', danger: '#B3261E' },
  ocean: { name: 'ocean', label: 'Ocean', primary: '#006A6A', primarySoft: '#B8F2F0', accent: '#315E72', background: '#F3F8F8', surface: '#FFFFFF', surfaceAlt: '#E9F6F6', text: '#142526', muted: '#627779', border: '#D5ECEC', danger: '#B3261E' },
  mint: { name: 'mint', label: 'Mint', primary: '#2E7D5B', primarySoft: '#C8F2DC', accent: '#36706A', background: '#F4F8F5', surface: '#FFFFFF', surfaceAlt: '#EBF7F0', text: '#18251F', muted: '#65756B', border: '#D9EBDD', danger: '#B3261E' },
  sky: { name: 'sky', label: 'Sky', primary: '#2674C8', primarySoft: '#D7E9FF', accent: '#4F6480', background: '#F5F8FC', surface: '#FFFFFF', surfaceAlt: '#F1F7FF', text: '#1B2938', muted: '#68798C', border: '#DFEBF7', danger: '#B3261E' },
  lavender: { name: 'lavender', label: 'Lavender', primary: '#6750A4', primarySoft: '#EADDFF', accent: '#7D5260', background: '#F8F6FB', surface: '#FFFFFF', surfaceAlt: '#F5F0FB', text: '#211B2B', muted: '#756B80', border: '#E9DFF4', danger: '#B3261E' },
  plum: { name: 'plum', label: 'Plum', primary: '#7A4E9B', primarySoft: '#EBD9F7', accent: '#6B5878', background: '#FAF6FC', surface: '#FFFFFF', surfaceAlt: '#F8F2FC', text: '#2A2031', muted: '#7A6C83', border: '#EDE1F3', danger: '#B3261E' },
  rose: { name: 'rose', label: 'Rose', primary: '#9C4168', primarySoft: '#FFD9E5', accent: '#70465A', background: '#FFF7F9', surface: '#FFFFFF', surfaceAlt: '#FFF0F4', text: '#291C23', muted: '#806A74', border: '#F1DDE5', danger: '#B3261E' },
  coral: { name: 'coral', label: 'Coral', primary: '#C85A4A', primarySoft: '#FFDDD6', accent: '#795B55', background: '#FFF8F6', surface: '#FFFFFF', surfaceAlt: '#FFF4F1', text: '#30211F', muted: '#806E68', border: '#F3E1DD', danger: '#B3261E' },
  amber: { name: 'amber', label: 'Amber', primary: '#9A6700', primarySoft: '#FFE2A8', accent: '#74602B', background: '#FFFBF2', surface: '#FFFFFF', surfaceAlt: '#FFF8EA', text: '#302817', muted: '#81745B', border: '#F1E5C9', danger: '#B3261E' },
  teal: { name: 'teal', label: 'Teal', primary: '#00796B', primarySoft: '#B8EEE6', accent: '#3F6864', background: '#F3F9F8', surface: '#FFFFFF', surfaceAlt: '#EBF8F5', text: '#182724', muted: '#647773', border: '#D7ECE8', danger: '#B3261E' }
};

export type Theme = Palette & { mode: 'light' | 'dark' };

export function resolveTheme(name: PaletteName, mode: 'light' | 'dark'): Theme {
  const p = palettes[name];
  if (mode === 'light') return { ...p, mode };
  return {
    ...p,
    mode,
    background: '#111B15',
    surface: '#17251C',
    surfaceAlt: '#283C30',
    text: '#F1F5F1',
    muted: '#A5B3A9',
    border: '#34483B'
  };
}
