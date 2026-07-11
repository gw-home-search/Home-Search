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

export function HelpIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><circle cx="12" cy="12" r="9" /><path d="M9.8 9a2.4 2.4 0 1 1 3.5 2.1c-.9.5-1.3 1-1.3 2" /><path d="M12 17h.01" /></svg>;
}

export function CheckIcon(props: IconProps) {
  return <svg {...sharedProps} {...props}><path d="m5 12 4.2 4.2L19 6.5" /></svg>;
}
