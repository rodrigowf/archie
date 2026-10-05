/** The Archie mark, copied from the mockups' `<symbol id="mark">`. Gallery-only copy (the app's lives in W-03/W-07). */
export function ArchieMark({ size = 40, className }: { size?: number; className?: string }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 48 48" aria-hidden="true">
      <rect width="48" height="48" rx="15" style={{ fill: 'var(--md-sys-color-primary-container)' }} />
      <path
        d="M14.5 35.5V23.5a9.5 9.5 0 0 1 19 0v12"
        style={{ fill: 'none', stroke: 'var(--md-sys-color-on-primary-container)', strokeWidth: 4.6, strokeLinecap: 'round' }}
      />
      <circle cx="24" cy="27" r="3.4" style={{ fill: 'var(--md-sys-color-tertiary)' }} />
    </svg>
  );
}
