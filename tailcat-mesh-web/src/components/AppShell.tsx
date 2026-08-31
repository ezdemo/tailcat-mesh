import { useState, type ReactNode } from 'react'
import {
  Activity,
  ArrowRightLeft,
  Cable,
  KeyRound,
  LayoutDashboard,
  LogOut,
  Menu,
  MonitorSmartphone,
  Network,
  Server,
  Settings2,
  X,
} from 'lucide-react'
import { cn } from './ui'

export type ViewId = 'overview' | 'devices' | 'networks' | 'services' | 'forwards' | 'connections' | 'tokens'

const navigation: Array<{ id: ViewId; label: string; description: string; icon: typeof LayoutDashboard }> = [
  { id: 'overview', label: '总览', description: '控制面状态', icon: LayoutDashboard },
  { id: 'devices', label: '设备', description: '审批与状态', icon: MonitorSmartphone },
  { id: 'networks', label: '网络', description: 'Virtual LAN', icon: Network },
  { id: 'services', label: '服务', description: '发布 TCP 服务', icon: Server },
  { id: 'forwards', label: '转发', description: '本地访问远端服务', icon: ArrowRightLeft },
  { id: 'connections', label: '连接', description: 'Direct / DERP 路径', icon: Cable },
  { id: 'tokens', label: '加入凭证', description: 'Enrollment Token', icon: KeyRound },
]

export function AppShell({
  view,
  onNavigate,
  onLogout,
  username,
  apiBaseUrl,
  children,
}: {
  view: ViewId
  onNavigate: (view: ViewId) => void
  onLogout: () => void
  username: string
  apiBaseUrl: string
  children: ReactNode
}) {
  const [mobileOpen, setMobileOpen] = useState(false)

  function navigate(nextView: ViewId) {
    onNavigate(nextView)
    setMobileOpen(false)
  }

  const nav = (
    <>
      <div className="flex h-[72px] items-center gap-3 border-b border-slate-200/80 px-5">
        <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-ink text-white">
          <Network className="h-5 w-5" aria-hidden="true" />
        </div>
        <div>
          <div className="text-sm font-bold tracking-tight text-ink">Tailcat Mesh</div>
          <div className="text-xs text-slate-400">Control Plane</div>
        </div>
        <button className="ml-auto flex min-h-11 min-w-11 items-center justify-center rounded-lg p-2 text-slate-500 transition hover:bg-slate-100 hover:text-ink md:hidden" onClick={() => setMobileOpen(false)} aria-label="关闭菜单">
          <X className="h-5 w-5" aria-hidden="true" />
        </button>
      </div>

      <div className="px-3 py-7">
        <p className="px-3 font-mono text-[11px] font-medium uppercase tracking-[0.18em] text-slate-500">Workspace</p>
        <nav className="mt-4 space-y-1" aria-label="主导航">
          {navigation.map((item) => {
            const Icon = item.icon
            const active = item.id === view
            return (
              <button
                key={item.id}
                onClick={() => navigate(item.id)}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'group flex min-h-12 w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition duration-200 ease-anthropic',
                  active ? 'bg-ink text-white' : 'text-slate-600 hover:bg-slate-100 hover:text-ink',
                )}
              >
                <Icon className={cn('h-5 w-5 shrink-0', active ? 'text-white' : 'text-slate-500 group-hover:text-ink')} aria-hidden="true" />
                <span className="min-w-0">
                  <span className="block text-sm font-semibold">{item.label}</span>
                  <span className={cn('mt-0.5 block text-xs', active ? 'text-slate-300' : 'text-slate-500')}>{item.description}</span>
                </span>
              </button>
            )
          })}
        </nav>
      </div>

      <div className="mt-auto px-5 pb-5">
        <div className="rounded-lg bg-slate-50 p-4 ring-1 ring-slate-200">
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-700">
            <Activity className="h-4 w-4 text-emerald-400" aria-hidden="true" />
            控制面已连接
          </div>
          <p className="mt-2 truncate text-xs text-slate-500" title={apiBaseUrl || '当前前端代理'}>
            {apiBaseUrl || 'Vite 代理 → localhost:8080'}
          </p>
        </div>
        <button onClick={onLogout} className="mt-4 flex min-h-11 w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold text-slate-600 transition duration-200 ease-anthropic hover:bg-slate-100 hover:text-ink">
          <LogOut className="h-4 w-4" aria-hidden="true" />
          退出登录
        </button>
      </div>
    </>
  )

  return (
    <div className="min-h-screen bg-paper">
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-64 flex-col border-r border-slate-200/80 bg-paper-raised md:flex">{nav}</aside>

      {mobileOpen && (
        <div className="fixed inset-0 z-50 md:hidden">
          <button className="fixed inset-0 bg-ink/60" onClick={() => setMobileOpen(false)} aria-label="关闭菜单" />
          <aside className="relative flex h-full w-64 flex-col border-r border-slate-200/80 bg-paper-raised shadow-elevated">{nav}</aside>
        </div>
      )}

      <div className="md:pl-64">
        <header className="sticky top-0 z-30 flex h-[72px] items-center justify-between border-b border-slate-200/80 bg-paper/90 px-5 backdrop-blur sm:px-8 xl:px-12">
          <button className="flex min-h-11 min-w-11 items-center justify-center rounded-lg p-2 text-slate-500 transition hover:bg-white hover:text-ink md:hidden" onClick={() => setMobileOpen(true)} aria-label="打开菜单">
            <Menu className="h-5 w-5" aria-hidden="true" />
          </button>
          <div className="hidden items-center gap-2 text-xs text-slate-500 sm:flex">
            <Settings2 className="h-4 w-4" aria-hidden="true" />
            <span>管理员控制台</span>
            <span className="text-slate-300">/</span>
            <span className="font-medium text-slate-700">{username}</span>
          </div>
          <div className="ml-auto flex items-center gap-2 font-mono text-xs text-slate-500">
            <span className="h-2 w-2 rounded-full bg-emerald-500" aria-hidden="true" />
            <span>本地控制面</span>
          </div>
        </header>
        <main className="mx-auto max-w-[1440px] px-5 py-10 sm:px-8 xl:px-12">{children}</main>
      </div>
    </div>
  )
}
