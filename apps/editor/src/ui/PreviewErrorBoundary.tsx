import { Component, type ReactNode } from 'react'
import styles from './PreviewErrorBoundary.module.css'

/** Keeps a 3D preview's failure (no WebGL, a bad mesh) inside the preview, as a sentence. */
export class PreviewErrorBoundary extends Component<
  { children: ReactNode },
  { error: string | null }
> {
  state = { error: null as string | null }
  static getDerivedStateFromError(error: unknown) {
    return { error: error instanceof Error ? error.message : String(error) }
  }
  render() {
    if (this.state.error) {
      return <div className={styles.fallback}>3D preview unavailable: {this.state.error}</div>
    }
    return this.props.children
  }
}
