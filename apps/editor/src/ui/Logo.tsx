import { cx } from './cx'
import styles from './Logo.module.css'

/** The NetherForge soul flame. Same pixels as docs/public/logo.svg and the app icon. */
const FLAME =
  'M7 3h1v1H7zM7 4h2v1H7zM6 5h3v1H6zM5 6h4v1H5zM10 6h1v1h-1zM5 7h6v1H5zM4 8h8v3H4zM5 11h6v1H5zM6 12h4v1H6z'
const CORE = 'M7 8h1v1H7zM6 9h3v1H6zM6 10h4v1H6zM7 11h2v1H7z'

export function Logo({ size = 16, className }: { size?: number; className?: string }) {
  return (
    <svg
      className={cx(styles.logo, className)}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      shapeRendering="crispEdges"
      aria-hidden="true"
    >
      <rect width="16" height="16" rx="3.12" fill="#0b1418" shapeRendering="geometricPrecision" />
      <path d={FLAME} fill="#36d6e7" />
      <path d={CORE} fill="#d4fcff" />
      <path d="M3 13h10v1H3z" fill="#5a4130" />
    </svg>
  )
}
