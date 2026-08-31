import { useState, type FormEvent } from 'react'
import { ArrowRight, Eye, EyeOff, Network, Server, ShieldCheck } from 'lucide-react'
import { TailcatMeshApi, saveApiBaseUrl } from '../../api/client'
import type { AuthSession } from '../../types'
import { Button, Notice } from '../../components/ui'

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
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const api = new TailcatMeshApi(apiBaseUrl)
      const session = await api.login(username.trim(), password)
      saveApiBaseUrl(apiBaseUrl)
      onLogin(session, apiBaseUrl.trim().replace(/\/$/, ''), username.trim())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录失败，请稍后重试。')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="min-h-screen bg-slate-950 px-4 py-8 text-slate-950 sm:px-6 lg:px-8">
      <div className="mx-auto grid min-h-[calc(100vh-4rem)] max-w-6xl overflow-hidden rounded-3xl bg-white shadow-2xl shadow-indigo-950/20 lg:grid-cols-[1.05fr_0.95fr]">
        <section className="relative hidden overflow-hidden bg-indigo-600 p-10 text-white lg:flex lg:flex-col lg:justify-between">
          <div className="absolute -right-24 -top-24 h-80 w-80 rounded-full bg-indigo-400/30 blur-3xl" />
          <div className="absolute -bottom-32 -left-20 h-96 w-96 rounded-full bg-sky-300/20 blur-3xl" />
          <div className="relative">
            <div className="flex items-center gap-3">
              <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-white/15 ring-1 ring-white/20">
                <Network className="h-6 w-6" aria-hidden="true" />
              </div>
              <div>
                <p className="font-bold tracking-tight">Tailcat Mesh</p>
                <p className="text-xs text-indigo-100">Device Management + TCP Mesh</p>
              </div>
            </div>
            <div className="mt-24 max-w-md">
              <p className="text-sm font-semibold uppercase tracking-[0.2em] text-indigo-100">Control Plane</p>
              <h1 className="mt-5 text-4xl font-semibold leading-tight tracking-tight">让每一台设备的状态，都清晰可见。</h1>
              <p className="mt-6 text-base leading-7 text-indigo-100">
                管理设备注册、审批和连接状态。数据保留在你的控制面，客户端继续使用官方 Tailcat 数据平面。
              </p>
            </div>
          </div>
          <div className="relative grid gap-3 text-sm text-indigo-50 sm:grid-cols-2">
            <div className="rounded-2xl bg-white/10 p-4 ring-1 ring-white/10">
              <ShieldCheck className="h-5 w-5 text-indigo-100" aria-hidden="true" />
              <p className="mt-3 font-semibold">默认拒绝</p>
              <p className="mt-1 text-xs leading-5 text-indigo-100/80">新设备先注册，再由管理员审批。</p>
            </div>
            <div className="rounded-2xl bg-white/10 p-4 ring-1 ring-white/10">
              <Server className="h-5 w-5 text-indigo-100" aria-hidden="true" />
              <p className="mt-3 font-semibold">自托管</p>
              <p className="mt-1 text-xs leading-5 text-indigo-100/80">本地 H2 文件数据库，部署简单。</p>
            </div>
          </div>
        </section>

        <section className="flex items-center justify-center p-6 sm:p-12">
          <div className="w-full max-w-md">
            <div className="mb-10 lg:hidden">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-600 text-white">
                  <Network className="h-5 w-5" aria-hidden="true" />
                </div>
                <div>
                  <p className="font-bold tracking-tight text-slate-950">Tailcat Mesh</p>
                  <p className="text-xs text-slate-500">Control Plane</p>
                </div>
              </div>
            </div>
            <div>
              <p className="text-sm font-semibold text-indigo-600">欢迎回来</p>
              <h2 className="mt-2 text-3xl font-semibold tracking-tight text-slate-950">登录控制面</h2>
              <p className="mt-3 text-sm leading-6 text-slate-500">使用管理员账号管理设备和加入凭证。</p>
            </div>

            <form className="mt-8 space-y-5" onSubmit={submit}>
              {error && <Notice tone="error" message={error} />}
              <label className="block">
                <span className="text-sm font-semibold text-slate-700">控制面地址</span>
                <input
                  value={apiBaseUrl}
                  onChange={(event) => setApiBaseUrl(event.target.value)}
                  placeholder="留空：使用开发代理 → localhost:8080"
                  className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm text-slate-950 ring-1 ring-inset ring-slate-200 transition placeholder:text-slate-400 focus:bg-white focus:ring-2 focus:ring-indigo-600"
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
                  className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm text-slate-950 ring-1 ring-inset ring-slate-200 transition placeholder:text-slate-400 focus:bg-white focus:ring-2 focus:ring-indigo-600"
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
                    className="block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 pr-12 text-sm text-slate-950 ring-1 ring-inset ring-slate-200 transition placeholder:text-slate-400 focus:bg-white focus:ring-2 focus:ring-indigo-600"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((visible) => !visible)}
                    className="absolute inset-y-0 right-0 flex items-center px-4 text-slate-400 hover:text-slate-700"
                    aria-label={showPassword ? '隐藏密码' : '显示密码'}
                  >
                    {showPassword ? <EyeOff className="h-5 w-5" aria-hidden="true" /> : <Eye className="h-5 w-5" aria-hidden="true" />}
                  </button>
                </span>
              </label>
              <Button type="submit" loading={loading} className="w-full py-3">
                登录控制面
                {!loading && <ArrowRight className="h-4 w-4" aria-hidden="true" />}
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
