import type { SVGProps } from 'react';

type IconProps = SVGProps<SVGSVGElement>;

const sharedProps = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.75,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  viewBox: '0 0 24 24',
};

export function HomeSearchLogoIcon(props: IconProps) {
  return (
    <svg {...sharedProps} {...props}>
      <path d="M3 10L12 3L21 10V20C21 20.55 20.55 21 20 21H4C3.45 21 3 20.55 3 20V10Z" />
      <circle cx="12" cy="14" r="3" className="logo-search-detail" />
      <line x1="14.5" y1="16.5" x2="17" y2="19" className="logo-search-detail" />
    </svg>
  );
}

export function SearchIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M21 21l-4.35-4.35M11 18a7 7 0 100-14 7 7 0 000 14z" /></svg>;
}

export function RefreshIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M20 7v5h-5" /><path d="M18.4 16a8 8 0 1 1 .7-7.2L20 12" /></svg>;
}

export function ChevronDownIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="m7 9.5 5 5 5-5" /></svg>;
}

export function ChevronRightIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="m9 7 5 5-5 5" /></svg>;
}

export function BackIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="m15 18-6-6 6-6" /></svg>;
}

export function CloseIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M6 6l12 12M18 6 6 18" /></svg>;
}

export function PlusIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M12 5v14M5 12h14" /></svg>;
}

export function MinusIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M5 12h14" /></svg>;
}

export function MapToolsIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><circle cx="12" cy="12" r="7" /><path d="M12 2v4M12 18v4M2 12h4M18 12h4" /><circle cx="12" cy="12" r="1.5" /></svg>;
}

export function HelpIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><circle cx="12" cy="12" r="9" /><path d="M9.8 9a2.4 2.4 0 1 1 3.5 2.1c-.9.5-1.3 1-1.3 2" /><path d="M12 17h.01" /></svg>;
}

export function CheckIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="m5 12 4.2 4.2L19 6.5" /></svg>;
}

export function HeartIcon({ filled = false, ...props }: IconProps & { filled?: boolean }) {
  return <svg {...sharedProps} {...props}><path fill={filled ? 'currentColor' : 'none'} d="M20.8 4.8a5.4 5.4 0 0 0-7.6 0L12 6l-1.2-1.2a5.4 5.4 0 0 0-7.6 7.6L12 21l8.8-8.6a5.4 5.4 0 0 0 0-7.6Z" /></svg>;
}

export function MapGridIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><rect x="4" y="4" width="6" height="6" /><rect x="14" y="4" width="6" height="6" /><rect x="4" y="14" width="6" height="6" /><rect x="14" y="14" width="6" height="6" /></svg>;
}

export function NewTradeIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M6 3h9l3 3v15H6z" /><path d="M14 3v4h4M9 13h6M12 10v6" /></svg>;
}

export function NewsIcon(props: IconProps) {
  return (
    <svg {...sharedProps} {...props}>
      <path d="M5 4h12a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2V4Z" />
      <path d="M5 17H3V7h2M9 8h6M9 12h6M9 16h4" />
    </svg>
  );
}

export function HighestDealIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M4 19 9 14l3 3 8-10" /><path d="M15 7h5v5" /></svg>;
}

export function RecordHighIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M6 21V4M6 5h10l-2.5 3L16 11H6" /><path d="m10 17 3-3 2 2 4-5" /></svg>;
}

export function RiseIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="m4 17 5-5 4 3 7-8" /><path d="M15 7h5v5" /></svg>;
}

export function FallIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="m4 7 5 5 4-3 7 8" /><path d="M15 17h5v-5" /></svg>;
}

export function CancellationIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" /><path d="m9 9 6 6M15 9l-6 6" /></svg>;
}
