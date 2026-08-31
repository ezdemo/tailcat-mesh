import { useEffect, useMemo, useState } from 'react'
import { Activity, Cable, CircleAlert, RefreshCw, Route, Wifi } from 'lucide-react'
import type { TailcatMeshApi } from '../../api/client'
import { errorMessage, isUnauthorized } from '../../lib/errors'
import { formatDate, formatRelativeDate, shorten } from '../../lib/format'
import type { Connection, ConnectionPathType, ConnectionStatus } from '../../types'
import { useMessage } from '../../components/message'
import { Badge, Button, Card, EmptyState, LoadingState, PageHeader } from '../../components/ui'

const statusLabels: Record<ConnectionStatus, string> = {
  ONLINE: '在线',
  DEGRADED: '降级',
  OFFLINE: '离线',
  UNKNOWN: '未知',
  STOPPED: '已停止',
}

const statusStyles: Record<ConnectionStatus, string> = {
  ONLINE: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  DEGRADED: 'bg-amber-50 text-amber-700 ring-amber-600/20',
  OFFLINE: 'bg-slate-100 text-slate-600 ring-slate-500/20',
  UNKNOWN: 'bg-slate-100 text-slate-600 ring-slate-500/20',
  STOPPED: 'bg-rose-50 text-rose-700 ring-rose-600/20',
}

const pathLabels: Record<ConnectionPathType, string> = {
  DIRECT: 'Direct',
  DERP: 'DERP',
  OFFLINE: '离线',
  UNKNOWN: '未知',
}

export function ConnectionsPage({ api, onUnauthorized }: { api: TailcatMeshApi; onUnauthorized: () => void }) {
  const [connections, setConnections] = useState<Connection[]>([])
  const [loading, setLoading] = useState(true)
  const [lastLoadedAt, setLastLoadedAt] = useState<string | null>(null)
  const { showSuccess, showError } = useMessage()

  async function load(showRefresh = false, notify = false) {
    if (!showRefresh) setLoading(true)
    try {
      setConnections(await api.listConnections())
      setLastLoadedAt(new Date().toISOString())
      if (notify) showSuccess('连接数据已刷新。')
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      showError(errorMessage(reason), '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    const interval = window.setInterval(() => void load(true), 30_000)
    return () => window.clearInterval(interval)
  }, [api])

  const summary = useMemo(() => ({
    online: connections.filter((connection) => connection.status === 'ONLINE').length,
    direct: connections.filter((connection) => connection.pathType === 'DIRECT').length,
    derp: connections.filter((connection) => connection.pathType === 'DERP').length,
    unhealthy: connections.filter((connection) => connection.status !== 'ONLINE').length,
  }), [connections])

  return (
    <>
      <PageHeader
        eyebrow="Peer connectivity"
        title="连接"
        description="查看每台 Agent 到同一 Mesh 中远端 Peer 的可达性。DERP 是正常的中继路径，不代表连接不可用。"
        actions={<Button variant="secondary" onClick={() => void load(true, true)}><RefreshCw className="h-4 w-4" aria-hidden="true" />刷新</Button>}
      />

      <div className="mt-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <SummaryCard icon={Activity} label="在线 Peer" value={summary.online} tone="emerald" />
        <SummaryCard icon={Wifi} label="Direct 路径" value={summary.direct} tone="indigo" />
        <SummaryCard icon={Route} label="DERP 路径" value={summary.derp} tone="sky" />
        <SummaryCard icon={CircleAlert} label="需要关注" value={summary.unhealthy} tone="amber" />
      </div>

      <Card className="mt-6 overflow-hidden">
        {loading ? (
          <LoadingState />
        ) : connections.length === 0 ? (
          <EmptyState icon={Cable} title="还没有 Peer 路径状态" description="批准同一 Mesh 中的两台设备，并让 Agent 完成首次 Peer 检查后，连接会显示在这里。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full table-fixed divide-y divide-slate-100">
              <thead className="bg-slate-50/70">
                <tr>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 sm:px-6">连接</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">状态</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">路径</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">延迟</th>
                  <th className="hidden px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 md:table-cell">最近检查</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {connections.map((connection) => (
                  <tr key={`${connection.sourceDeviceId}-${connection.peerDeviceId}`} className="transition hover:bg-slate-50/70">
                    <td className="px-5 py-4 sm:px-6">
                      <div className="flex min-w-64 items-center gap-3">
                        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600"><Cable className="h-4 w-4" aria-hidden="true" /></div>
                        <div className="min-w-0">
                          <div className="flex items-center gap-2 text-sm font-semibold text-slate-900"><span className="truncate">{connection.sourceDeviceName}</span><span className="text-slate-300">→</span><span className="truncate">{connection.peerDeviceName}</span></div>
                          <div className="mt-1 font-mono text-[11px] text-slate-400" title={`${connection.sourceDeviceId} → ${connection.peerDeviceId}`}>{shorten(connection.sourceDeviceId, 8, 5)} → {shorten(connection.peerDeviceId, 8, 5)}</div>
                        </div>
                      </div>
                    </td>
                    <td className="whitespace-nowrap px-5 py-4"><Badge className={statusStyles[connection.status]}>{statusLabels[connection.status]}</Badge>{connection.lastError && <p className="mt-1 max-w-xs truncate text-[11px] text-rose-600" title={connection.lastError}>{connection.lastError}</p>}</td>
                    <td className="whitespace-nowrap px-5 py-4"><div className="flex items-center gap-2 text-sm font-semibold text-slate-700"><span className={connection.pathType === 'DIRECT' ? 'text-indigo-600' : connection.pathType === 'DERP' ? 'text-sky-600' : 'text-slate-400'}>{pathLabels[connection.pathType]}</span>{connection.derpRegion && <span className="text-xs font-normal text-slate-400">({connection.derpRegion})</span>}</div>{connection.directEndpoint && <p className="mt-1 font-mono text-[11px] text-slate-400">{connection.directEndpoint}</p>}</td>
                    <td className="whitespace-nowrap px-5 py-4 text-sm text-slate-700">{formatLatency(connection.latencyMs)}</td>
                    <td className="hidden whitespace-nowrap px-5 py-4 md:table-cell"><div className="text-xs font-medium text-slate-700">{formatRelativeDate(connection.lastCheckAt)}</div><div className="mt-1 text-[11px] text-slate-400">{formatDate(connection.lastCheckAt)}</div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {connections.length > 0 && lastLoadedAt && <p className="mt-4 text-xs text-slate-400">最后一次刷新：{formatDate(lastLoadedAt)}。Agent 默认每 30 秒检查一次 Peer 路径。</p>}
    </>
  )
}

function formatLatency(value: number | null): string {
  if (value === null || value < 0 || !Number.isFinite(value)) return '—'
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ms`
}

function SummaryCard({ icon: Icon, label, value, tone }: { icon: typeof Activity; label: string; value: number; tone: 'emerald' | 'indigo' | 'sky' | 'amber' }) {
  const styles = {
    emerald: 'bg-emerald-50 text-emerald-600',
    indigo: 'bg-indigo-50 text-indigo-600',
    sky: 'bg-sky-50 text-sky-600',
    amber: 'bg-amber-50 text-amber-600',
  }
  return <Card className="flex items-center gap-4 p-5"><div className={`flex h-10 w-10 items-center justify-center rounded-xl ${styles[tone]}`}><Icon className="h-5 w-5" aria-hidden="true" /></div><div><p className="text-xs font-medium text-slate-500">{label}</p><p className="mt-1 text-2xl font-semibold tracking-tight text-slate-950">{value}</p></div></Card>
}
