import { useEffect, useMemo, useState } from 'react'
import { AppShell, type ViewId } from './components/AppShell'
import { TailcatMeshApi, loadApiBaseUrl } from './api/client'
import { LoginPage } from './features/auth/LoginPage'
import { DashboardPage } from './features/dashboard/DashboardPage'
import { DevicesPage } from './features/devices/DevicesPage'
import { NetworksPage } from './features/networks/NetworksPage'
import { ServicesPage } from './features/services/ServicesPage'
import { ForwardsPage } from './features/forwards/ForwardsPage'
import { ConnectionsPage } from './features/connections/ConnectionsPage'
import { EnrollmentTokensPage } from './features/tokens/EnrollmentTokensPage'
import type { AuthSession } from './types'

const SESSION_STORAGE_KEY = 'tailcat-mesh.admin-session'
const USERNAME_STORAGE_KEY = 'tailcat-mesh.admin-username'

function readSession(): AuthSession | null {
  try {
    const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY)
    if (!raw) return null
    const session = JSON.parse(raw) as AuthSession
    if (!session.accessToken || !session.expiresAt || new Date(session.expiresAt).getTime() <= Date.now()) {
      window.sessionStorage.removeItem(SESSION_STORAGE_KEY)
      return null
    }
    return session
  } catch {
    return null
  }
}

function readUsername(): string {
  try {
    return window.sessionStorage.getItem(USERNAME_STORAGE_KEY) ?? 'admin'
  } catch {
    return 'admin'
  }
}

function routeFromHash(): ViewId {
  const value = window.location.hash.replace(/^#/, '')
  return value === 'devices' || value === 'networks' || value === 'services' || value === 'forwards' || value === 'connections' || value === 'tokens' ? value : 'overview'
}

function App() {
  const [session, setSession] = useState<AuthSession | null>(() => readSession())
  const [username, setUsername] = useState(() => readUsername())
  const [apiBaseUrl, setApiBaseUrl] = useState(() => loadApiBaseUrl())
  const [view, setView] = useState<ViewId>(() => routeFromHash())

  useEffect(() => {
    function onHashChange() {
      setView(routeFromHash())
    }
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const api = useMemo(() => new TailcatMeshApi(apiBaseUrl, session?.accessToken), [apiBaseUrl, session?.accessToken])

  function saveSession(nextSession: AuthSession, nextApiBaseUrl: string, nextUsername: string) {
    try {
      window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(nextSession))
      window.sessionStorage.setItem(USERNAME_STORAGE_KEY, nextUsername)
    } catch {
      // The application can continue with the in-memory session.
    }
    setSession(nextSession)
    setApiBaseUrl(nextApiBaseUrl)
    setUsername(nextUsername)
    window.location.hash = 'overview'
  }

  async function logout() {
    try {
      await api.logout()
    } catch {
      // A local logout should still complete if the Server is unavailable.
    } finally {
      clearSession()
    }
  }

  function clearSession() {
    try {
      window.sessionStorage.removeItem(SESSION_STORAGE_KEY)
      window.sessionStorage.removeItem(USERNAME_STORAGE_KEY)
    } catch {
      // Ignore storage failures.
    }
    setSession(null)
  }

  function navigate(nextView: ViewId) {
    window.location.hash = nextView
  }

  if (!session) {
    return <LoginPage initialApiBaseUrl={apiBaseUrl} onLogin={saveSession} />
  }

  return (
    <AppShell view={view} onNavigate={navigate} onLogout={() => void logout()} username={username} apiBaseUrl={apiBaseUrl}>
      {view === 'overview' && <DashboardPage api={api} onNavigate={navigate} onUnauthorized={clearSession} />}
      {view === 'devices' && <DevicesPage api={api} onUnauthorized={clearSession} />}
      {view === 'networks' && <NetworksPage api={api} onUnauthorized={clearSession} />}
      {view === 'services' && <ServicesPage api={api} onUnauthorized={clearSession} />}
      {view === 'forwards' && <ForwardsPage api={api} onUnauthorized={clearSession} />}
      {view === 'connections' && <ConnectionsPage api={api} onUnauthorized={clearSession} />}
      {view === 'tokens' && <EnrollmentTokensPage api={api} onUnauthorized={clearSession} />}
    </AppShell>
  )
}

export default App
