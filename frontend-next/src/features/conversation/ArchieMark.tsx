/**
 * The Archie mark (mockups `<symbol id="mark">`) for the empty state. A feature-local copy: features
 * may not import from `src/app` (spec 13 §3.9).
 */
export function ArchieMark({ size = 24, className }: { size?: number; className?: string }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 48 48" aria-hidden="true" focusable="false">
      <rect width="48" height="48" rx="15" style={{ fill: 'var(--md-sys-color-primary-container)' }} />
      <path
        d="M14.5 35.5V23.5a9.5 9.5 0 0 1 19 0v12"
        style={{ fill: 'none', stroke: 'var(--md-sys-color-on-primary-container)', strokeWidth: 4.6, strokeLinecap: 'round' }}
      />
      <circle cx="24" cy="27" r="3.4" style={{ fill: 'var(--md-sys-color-tertiary)' }} />
    </svg>
  );
}

/** "Good morning." / "Good afternoon." / "Good evening." (mockups phone (i)). */
export function greeting(now: Date = new Date()): string {
  const h = now.getHours();
  if (h >= 5 && h < 12) return 'Good morning.';
  if (h >= 12 && h < 18) return 'Good afternoon.';
  return 'Good evening.';
}
