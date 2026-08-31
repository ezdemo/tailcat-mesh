import { useEffect, useMemo, useState, type ComponentType } from 'react'
import { ArrowRight, ArrowRightLeft, Clock3, KeyRound, MonitorSmartphone, RefreshCw, Server, ShieldCheck, Wifi } from 'lucide-react'
import type { TailcatMeshApi } from '../../api/client'
import { errorMessage, isUnauthorized } from '../../lib/errors'
import { formatDate, formatRelativeDate, isTokenActive, shorten, statusLabels, statusStyles } from '../../lib/format'
import type { Device, EnrollmentToken, Forward, Service } from '../../types'
import type { ViewId } from '../../components/AppShell'
import { useMessage } from '../../components/message'
import { Badge, Button, Card, PageHeader, Skeleton, Spinner, cn } from '../../components/ui'

export function DashboardPage({
  api,
  onNavigate,
  onUnauthorized,
}: {
  api: TailcatMeshApi
  onNavigate: (view: ViewId) => void
  onUnauthorized: () => void
}) {
  const [devices, setDevices] = useState<Device[]>([])
  const [tokens, setTokens] = useState<EnrollmentToken[]>([])
  const [services, setServices] = useState<Service[]>([])
  const [forwards, setForwards] = useState<Forward[]>([])
  const [loading, setLoading] = useState(true)
  const { showSuccess, showError } = useMessage()

  async function load(showRefresh = false, notify = false) {
    if (!showRefresh) setLoading(true)
    try {
      const [nextDevices, nextTokens, nextServices, nextForwards] = await Promise.all([
        api.listDevices(),
        api.listEnrollmentTokens(),
        api.listServices(),
        api.listForwards(),
      ])
      setDevices(nextDevices)
      setTokens(nextTokens)
      setServices(nextServices)
      setForwards(nextForwards)
      if (notify) showSuccess('总览已刷新。')
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      showError(errorMessage(reason), '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [api])

  const stats = useMemo(() => ({
    total: devices.length,
    online: devices.filter((device) => device.status === 'ONLINE').length,
    pending: devices.filter((device) => device.status === 'PENDING').length,
    activeTokens: tokens.filter((token) => isTokenActive(token.expiresAt, token.enabled)).length,
    services: services.filter((service) => service.enabled).length,
    activeForwards: forwards.filter((forward) => forward.enabled && forward.status === 'READY').length,
  }), [devices, tokens, services, forwards])

  const recentDevices = useMemo(() => [...devices].sort((a, b) => {
    return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
  }).slice(0, 5), [devices])

  return (
    <>
      <PageHeader
        eyebrow="Workspace overview"
        title="控制面总览"
        description="查看设备健康状态、待处理审批和加入凭证。"
        actions={<Button variant="secondary" onClick={() => void load(true, true)}><RefreshCw className="h-4 w-4" aria-hidden="true" />刷新</Button>}
      />

      {loading ? (
        <DashboardLoading />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            <StatCard label="设备总数" value={stats.total} detail="已登记设备" icon={MonitorSmartphone} tint="indigo" />
            <StatCard label="在线设备" value={stats.online} detail={stats.total ? `${Math.round((stats.online / stats.total) * 100)}% 在线率` : '等待 Agent 上报'} icon={Wifi} tint="emerald" />
            <StatCard label="待审批" value={stats.pending} detail={stats.pending ? '需要管理员处理' : '没有待处理申请'} icon={ShieldCheck} tint="amber" />
            <StatCard label="有效加入凭证" value={stats.activeTokens} detail="可继续邀请设备" icon={KeyRound} tint="violet" />
            <StatCard label="服务" value={stats.services} detail="已启用的 TCP 服务" icon={Server} tint="indigo" />
            <StatCard label="活动转发" value={stats.activeForwards} detail="本地监听已就绪" icon={ArrowRightLeft} tint="emerald" />
          </div>

          <div className="mt-6 grid gap-6 xl:grid-cols-[1.35fr_0.65fr]">
            <Card className="min-h-[340px] overflow-hidden">
              <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4 sm:px-6">
                <div>
                  <h2 className="text-sm font-semibold text-slate-950">最近设备</h2>
                  <p className="mt-1 text-xs text-slate-500">按最近一次状态变更排序</p>
                </div>
                <Button variant="ghost" className="px-2 text-xs" onClick={() => onNavigate('devices')}>查看全部 <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" /></Button>
              </div>
              {recentDevices.length === 0 ? (
                <div className="px-6 py-14 text-center">
                  <MonitorSmartphone className="mx-auto h-8 w-8 text-slate-300" aria-hidden="true" />
                  <p className="mt-3 text-sm font-semibold text-slate-700">还没有设备</p>
                  <p className="mt-1 text-xs text-slate-500">创建加入凭证后，让第一台 Agent 注册进来。</p>
                  <Button className="mt-5" onClick={() => onNavigate('tokens')}>创建加入凭证</Button>
                </div>
              ) : (
                <div className="divide-y divide-slate-100">
                  {recentDevices.map((device) => <DeviceRow key={device.id} device={device} onClick={() => onNavigate('devices')} />)}
                </div>
              )}
            </Card>

            <Card className="min-h-[340px] p-6">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-xs font-bold uppercase tracking-[0.18em] text-indigo-600">加入流程</p>
                  <h2 className="mt-2 text-lg font-semibold tracking-tight text-slate-950">邀请一台新设备</h2>
                </div>
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600"><KeyRound className="h-5 w-5" aria-hidden="true" /></div>
              </div>
              <ol className="mt-6 space-y-5">
                <Step number="01" title="创建加入凭证" done={stats.activeTokens > 0} />
                <Step number="02" title="用户运行 Agent connect" done={devices.length > 0} />
                <Step number="03" title="审批待加入设备" done={stats.pending === 0 && devices.length > 0} />
              </ol>
              <Button className="mt-7 w-full" onClick={() => onNavigate('tokens')}>管理加入凭证 <ArrowRight className="h-4 w-4" aria-hidden="true" /></Button>
            </Card>
          </div>
        </>
      )}
    </>
  )
}

function DashboardLoading() {
  return (
    <div className="space-y-6" role="status" aria-label="加载中">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 6 }, (_, index) => (
          <Card key={index} className="min-h-[136px] p-5">
            <div className="flex items-start justify-between gap-4">
              <div className="space-y-3">
                <Skeleton className="h-3 w-20" />
                <Skeleton className="h-8 w-12" />
              </div>
              <Skeleton className="h-10 w-10 rounded-xl" />
            </div>
            <Skeleton className="mt-4 h-2 w-28" />
          </Card>
        ))}
      </div>

      <div className="grid gap-6 xl:grid-cols-[1.35fr_0.65fr]">
        <Card className="min-h-[340px] overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4 sm:px-6">
            <div className="space-y-2">
              <Skeleton className="h-3 w-20" />
              <Skeleton className="h-2 w-36" />
            </div>
            <Spinner className="h-4 w-4" />
          </div>
          <div className="divide-y divide-slate-100 px-5 sm:px-6">
            {Array.from({ length: 4 }, (_, index) => (
              <div key={index} className="flex min-h-14 items-center gap-4 py-3">
                <Skeleton className="h-10 w-10 shrink-0 rounded-xl" />
                <div className="min-w-0 flex-1 space-y-2">
                  <Skeleton className="h-3 w-1/3 min-w-28" />
                  <Skeleton className="h-2 w-2/3 min-w-40" />
                </div>
                <Skeleton className="h-6 w-16 shrink-0 rounded-full" />
              </div>
            ))}
          </div>
        </Card>

        <Card className="min-h-[340px] p-6">
          <div className="flex items-start justify-between gap-4">
            <div className="space-y-3">
              <Skeleton className="h-3 w-20" />
              <Skeleton className="h-5 w-36" />
            </div>
            <Skeleton className="h-10 w-10 rounded-xl" />
          </div>
          <div className="mt-8 space-y-5">
            {Array.from({ length: 3 }, (_, index) => (
              <div key={index} className="flex items-center gap-3">
                <Skeleton className="h-8 w-8 shrink-0 rounded-full" />
                <Skeleton className="h-3 w-40" />
              </div>
            ))}
          </div>
          <Skeleton className="mt-8 h-11 w-full rounded-lg" />
        </Card>
      </div>
    </div>
  )
}

function StatCard({ label, value, detail, icon: Icon, tint }: {
  label: string
  value: number
  detail: string
  icon: ComponentType<{ className?: string }>
  tint: 'indigo' | 'emerald' | 'amber' | 'violet'
}) {
  const styles = {
    indigo: 'bg-indigo-50 text-indigo-600',
    emerald: 'bg-emerald-50 text-emerald-600',
    amber: 'bg-amber-50 text-amber-600',
    violet: 'bg-violet-50 text-violet-600',
  }
  return (
    <Card className="min-h-[136px] p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-medium text-slate-500">{label}</p>
          <p className="mt-3 text-3xl font-semibold tracking-tight text-slate-950">{value}</p>
        </div>
        <div className={cn('flex h-10 w-10 items-center justify-center rounded-xl', styles[tint])}><Icon className="h-5 w-5" aria-hidden="true" /></div>
      </div>
      <p className="mt-4 text-xs text-slate-400">{detail}</p>
    </Card>
  )
}

function DeviceRow({ device, onClick }: { device: Device; onClick: () => void }) {
  return (
    <button onClick={onClick} className="flex w-full items-center gap-4 px-5 py-4 text-left transition hover:bg-slate-50 sm:px-6">
      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-slate-100 text-slate-500"><MonitorSmartphone className="h-5 w-5" aria-hidden="true" /></div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="truncate text-sm font-semibold text-slate-900">{device.name}</p>
          <Badge className={statusStyles[device.status]}>{statusLabels[device.status]}</Badge>
        </div>
        <p className="mt-1 truncate text-xs text-slate-500">{device.hostname} · {device.os}/{device.arch}</p>
      </div>
      <div className="hidden shrink-0 text-right sm:block">
        <p className="text-xs font-medium text-slate-700">{formatRelativeDate(device.lastSeenAt)}</p>
        <p className="mt-1 text-[11px] text-slate-400">{formatDate(device.lastSeenAt)}</p>
      </div>
      <ArrowRight className="h-4 w-4 shrink-0 text-slate-300" aria-hidden="true" />
    </button>
  )
}

function Step({ number, title, done }: { number: string; title: string; done: boolean }) {
  return (
    <li className="flex items-center gap-3">
      <span className={cn('flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[11px] font-bold', done ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500')}>{number}</span>
      <span className={cn('text-sm font-medium', done ? 'text-slate-900' : 'text-slate-500')}>{title}</span>
      {done && <span className="ml-auto text-xs font-semibold text-emerald-600">完成</span>}
      {!done && <Clock3 className="ml-auto h-4 w-4 text-slate-300" aria-hidden="true" />}
    </li>
  )
}
