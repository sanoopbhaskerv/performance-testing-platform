import { useEffect, useState } from 'react';
import { Activity } from 'lucide-react';
import { applyBranding, DEFAULT_BRANDING, loadBranding } from './branding';

export default function BrandHeader() {
  const [branding, setBranding] = useState(DEFAULT_BRANDING);
  const [failedLogo, setFailedLogo] = useState('');
  useEffect(() => {
    let active = true;
    loadBranding().then(config => { if (active) setBranding(config); }).catch(() => {
      // A missing optional configuration must not prevent sign-in or hide test evidence.
    });
    return () => { active = false; };
  }, []);
  useEffect(() => applyBranding(branding), [branding]);
  return <div className="header-brand">
    {branding.logoUrl && failedLogo !== branding.logoUrl
      ? <img className="brand-logo" src={branding.logoUrl} alt={branding.logoAlt}
          onError={() => setFailedLogo(branding.logoUrl)} />
      : <Activity className="logo-icon" size={28} aria-hidden />}
    <span>{branding.name}</span>
  </div>;
}
