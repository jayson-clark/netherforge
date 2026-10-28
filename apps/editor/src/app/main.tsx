import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBackend } from './backend'
import './styles/tokens.css'
import './styles/base.css'
import { App } from './App'
import styles from './App.module.css'
import { AppProvider, createApp } from '@/state/providers'

async function start() {
  const root = createRoot(document.getElementById('root')!)
  try {
    const app = createApp(await createBackend())
    // The zoom the editor was left at, before the first frame; never worth failing to start over.
    await app.zoom.apply().catch(() => {})
    await Promise.all([
      app.workspace.getState().init(),
      app.run.getState().connect(),
      app.network.getState().connect(),
      app.profiler.getState().connect(),
      app.debug.getState().connect(),
    ])
    // Quietly: a dev build or a machine offline has nothing to say here.
    void app.updates.getState().check({ quiet: true })
    root.render(
      <StrictMode>
        <AppProvider app={app}>
          <App />
        </AppProvider>
      </StrictMode>,
    )
  } catch (error) {
    root.render(
      <pre className={styles.fatal}>
        NetherForge couldn&apos;t start: {error instanceof Error ? error.message : String(error)}
      </pre>,
    )
  }
}

void start()
