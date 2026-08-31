import { useState, type FormEvent } from 'react'
import { ArrowRight, Eye, EyeOff, Network, Server, ShieldCheck } from 'lucide-react'
import { TailcatMeshApi, saveApiBaseUrl } from '../../api/client'
import type { AuthSession } from '../../types'
import { useMessage } from '../../components/message'
import { Button } from '../../components/ui'

export function LoginPage({
  initialApiBaseUrl,
  onLogin,
}: {
  initialApiBaseUrl: string
  onLogin: (session: AuthSession, apiBaseUrl: string, username: string) => void
}) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [apiBaseUrl, setApiBaseUrl] = useState(initialApiBaseUrl)
  const [showPassword, setShowPassword] = useState(false)
  const { showError } = useMessage()

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    try {
      const api = new TailcatMeshApi(apiBaseUrl)
      const session = await api.login(username.trim(), password)
      saveApiBaseUrl(apiBaseUrl)
      onLogin(session, apiBaseUrl.trim().replace(/\/$/, ''), username.trim())
    } catch (reason) {
      showError(reason instanceof Error ? reason.message : '请稍后重试。', '登录失败')
    }
  }

  return (
    <main className="min-h-screen bg-paper px-4 py-6 text-ink sm:px-6 lg:px-8">
      <div className="mx-auto grid min-h-[calc(100vh-3rem)] max-w-7xl overflow-hidden rounded-lg bg-paper-raised shadow-card ring-1 ring-slate-200/80 md:grid-cols-[1.08fr_0.92fr]">
        <section className="relative hidden overflow-hidden bg-ink p-8 text-white md:flex md:flex-col md:justify-between lg:p-12">
          <div className="relative flex h-full flex-col">
            <div className="flex items-center gap-3">
              <div className="flex h-11 w-11 items-center justify-center rounded-lg bg-white text-ink">
                <Network className="h-6 w-6" aria-hidden="true" />
              </div>
              <div>
                <p className="text-sm font-bold tracking-tight">Tailcat Mesh</p>
                <p className="font-mono text-xs text-slate-400">DEVICE MANAGEMENT / TCP MESH</p>
              </div>
            </div>

            <div className="mt-20 max-w-xl md:mt-auto md:pb-20">
              <p className="font-mono text-[11px] font-medium uppercase tracking-[0.18em] text-slate-400">Control plane / 01</p>
              <h1 className="mt-6 text-[clamp(2.75rem,6vw,3.625rem)] font-bold leading-[1.1] tracking-[-0.05em]">让每一台设备的状态，都清晰可见。</h1>
              <p className="mt-7 max-w-lg text-sm leading-6 text-slate-300">
                管理设备注册、审批和连接状态。数据保留在你的控制面，客户端继续使用官方 Tailcat 数据平面。
              </p>
            </div>

            <div className="grid gap-6 border-t border-white/15 pt-5 text-sm text-slate-300 sm:grid-cols-2">
              <div>
                <ShieldCheck className="h-5 w-5 text-white" aria-hidden="true" />
                <p className="mt-3 font-semibold text-white">默认拒绝</p>
                <p className="mt-1 text-xs leading-5 text-slate-400">新设备先注册，再由管理员审批。</p>
              </div>
              <div>
                <Server className="h-5 w-5 text-white" aria-hidden="true" />
                <p className="mt-3 font-semibold text-white">自托管</p>
                <p className="mt-1 text-xs leading-5 text-slate-400">本地 H2 文件数据库，部署简单。</p>
              </div>
            </div>
          </div>
        </section>

        <section className="flex items-center justify-center bg-paper-raised p-6 sm:p-12 lg:p-16">
          <div className="w-full max-w-md">
            <div className="mb-12 md:hidden">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-ink text-white">
                  <Network className="h-5 w-5" aria-hidden="true" />
                </div>
                <div>
                  <p className="text-sm font-bold tracking-tight text-ink">Tailcat Mesh</p>
                  <p className="font-mono text-xs text-slate-500">CONTROL PLANE</p>
                </div>
              </div>
            </div>
            <div>
              <p className="font-mono text-[11px] font-medium uppercase tracking-[0.18em] text-slate-500">Welcome back</p>
              <h2 className="mt-4 text-[clamp(2.25rem,5vw,3.625rem)] font-bold leading-[1.1] tracking-[-0.05em] text-ink">登录控制面</h2>
              <p className="mt-4 text-sm leading-5 text-slate-500">使用管理员账号管理设备和加入凭证。</p>
            </div>

            <form className="mt-10 space-y-5" onSubmit={submit}>
              <label className="block">
                <span className="text-sm font-semibold text-slate-700">控制面地址</span>
                <input
                  value={apiBaseUrl}
                  onChange={(event) => setApiBaseUrl(event.target.value)}
                  placeholder="留空：使用开发代理 → localhost:8080"
                  className="mt-2 block min-h-11 w-full rounded-lg border-0 bg-slate-50 px-4 py-3 text-sm text-ink ring-1 ring-inset ring-slate-200 transition duration-200 ease-anthropic placeholder:text-slate-400 focus:bg-white focus:ring-2 focus:ring-ink"
                  inputMode="url"
                />
                <span className="mt-2 block text-xs text-slate-400">生产环境可填写完整的 HTTPS 控制面地址。</span>
              </label>
              <label className="block">
                <span className="text-sm font-semibold text-slate-700">用户名</span>
                <input
                  required
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="username"
                  className="mt-2 block min-h-11 w-full rounded-lg border-0 bg-slate-50 px-4 py-3 text-sm text-ink ring-1 ring-inset ring-slate-200 transition duration-200 ease-anthropic placeholder:text-slate-400 focus:bg-white focus:ring-2 focus:ring-ink"
                />
              </label>
              <label className="block">
                <span className="text-sm font-semibold text-slate-700">密码</span>
                <span className="relative mt-2 block">
                  <input
                    required
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    autoComplete="current-password"
                    className="block min-h-11 w-full rounded-lg border-0 bg-slate-50 px-4 py-3 pr-12 text-sm text-ink ring-1 ring-inset ring-slate-200 transition duration-200 ease-anthropic placeholder:text-slate-400 focus:bg-white focus:ring-2 focus:ring-ink"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((visible) => !visible)}
                    className="absolute inset-y-0 right-0 flex min-w-11 items-center justify-center text-slate-400 transition hover:text-ink"
                    aria-label={showPassword ? '隐藏密码' : '显示密码'}
                  >
                    {showPassword ? <EyeOff className="h-5 w-5" aria-hidden="true" /> : <Eye className="h-5 w-5" aria-hidden="true" />}
                  </button>
                </span>
              </label>
              <Button type="submit" className="w-full py-3">
                登录控制面
                <ArrowRight className="h-4 w-4" aria-hidden="true" />
              </Button>
            </form>

            <p className="mt-8 text-center text-xs leading-5 text-slate-400">
              本地测试默认账号为 <span className="font-semibold text-slate-500">admin</span>，请在生产环境修改默认密码。
            </p>
          </div>
        </section>
      </div>
    </main>
  )
}
