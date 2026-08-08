export function MapPinIcon({ selected = false }: { selected?: boolean }) {
  if (selected) {
    return (
      <svg aria-hidden="true" fill="none" height="14" viewBox="0 0 16 16" width="14">
        <path d="m3 8 3 3 7-7" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.7" />
      </svg>
    );
  }
  return (
    <svg aria-hidden="true" fill="none" height="14" viewBox="0 0 16 16" width="14">
      <path d="M13 6.5c0 3.25-5 7-5 7s-5-3.75-5-7a5 5 0 0 1 10 0Z" stroke="currentColor" strokeWidth="1.4" />
      <circle cx="8" cy="6.5" r="1.6" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  );
}
