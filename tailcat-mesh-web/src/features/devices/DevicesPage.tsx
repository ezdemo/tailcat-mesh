import { useEffect, useMemo, useState } from 'react'
import { Check, ChevronRight, Copy, Laptop, Network, RefreshCw, Search, ShieldAlert, ShieldCheck, XCircle } from 'lucide-react'
import type { TailcatMeshApi } from '../../api/client'
import { errorMessage, isUnauthorized } from '../../lib/errors'
import { formatDate, formatRelativeDate, shorten, statusLabels, statusStyles } from '../../lib/format'
import type { Device, DeviceStatus, DeviceVirtualNetwork } from '../../types'
import { Badge, Button, Card, EmptyState, Modal, Notice, PageHeader, Spinner, cn } from '../../components/ui'

const filters: Array<{ value: 'ALL' | DeviceStatus; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING', label: '待审批' },
  { value: 'ONLINE', label: '在线' },
  { value: 'OFFLINE', label: '离线' },
  { value: 'DISABLED', label: '已禁用' },
]

export function DevicesPage({ api, onUnauthorized }: { api: TailcatMeshApi; onUnauthorized: () => void }) {
  const [devices, setDevices] = useState<Device[]>([])
  const [selectedDevice, setSelectedDevice] = useState<Device | null>(null)
  const [selectedVirtualNetworks, setSelectedVirtualNetworks] = useState<DeviceVirtualNetwork[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [actionId, setActionId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<'ALL' | DeviceStatus>('ALL')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<{ type: 'approve' | 'disable'; device: Device } | null>(null)

  async function load(showRefresh = false) {
    setError(null)
    showRefresh ? setRefreshing(true) : setLoading(true)
    try {
      const nextDevices = await api.listDevices()
      setDevices(nextDevices)
      setSelectedDevice((current) => current ? nextDevices.find((device) => device.id === current.id) ?? current : null)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }

  useEffect(() => {
    void load()
  }, [api])

  const filteredDevices = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase()
    return devices.filter((device) => {
      const matchesStatus = status === 'ALL' || device.status === status
      const matchesQuery = !normalizedQuery || [device.name, device.hostname, device.os, device.arch, device.id]
        .some((value) => value.toLowerCase().includes(normalizedQuery))
      return matchesStatus && matchesQuery
    })
  }, [devices, query, status])

  async function openDetails(device: Device) {
    setSelectedDevice(device)
    setSelectedVirtualNetworks(device.virtualNetworks ?? [])
    try {
      const [nextDevice, virtualNetworks] = await Promise.all([
        api.getDevice(device.id),
        api.listDeviceVirtualNetworks(device.id),
      ])
      setSelectedDevice(nextDevice)
      setSelectedVirtualNetworks(virtualNetworks)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    }
  }

  async function performAction() {
    if (!confirm) return
    const { type, device } = confirm
    setConfirm(null)
    setActionId(device.id)
    setError(null)
    try {
      const updated = type === 'approve' ? await api.approveDevice(device.id) : await api.disableDevice(device.id)
      setDevices((current) => current.map((item) => item.id === updated.id ? updated : item))
      setSelectedDevice(updated)
      setSelectedVirtualNetworks(updated.virtualNetworks ?? [])
      setNotice(type === 'approve' ? `${device.name} 已通过审批。` : `${device.name} 已禁用。`)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setActionId(null)
    }
  }

  async function copyValue(value: string, label: string) {
    try {
      await navigator.clipboard.writeText(value)
      setNotice(`${label}已复制。`)
    } catch {
      setError(`无法复制${label}，请手动选择文本。`)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="Device registry"
        title="设备"
        description="审批加入申请，查看 Agent 心跳和 Tailcat 运行状态。"
        actions={<Button variant="secondary" loading={refreshing} onClick={() => void load(true)}><RefreshCw className="h-4 w-4" aria-hidden="true" />刷新</Button>}
      />

      <div className="space-y-4">
        {error && <Notice tone="error" title="操作失败" message={error} onClose={() => setError(null)} />}
        {notice && <Notice tone="success" message={notice} onClose={() => setNotice(null)} />}
      </div>

      <Card className="mt-6 overflow-hidden">
        <div className="flex flex-col gap-4 border-b border-slate-100 px-5 py-4 sm:px-6 md:flex-row md:items-center md:justify-between">
          <div className="relative w-full md:max-w-xs">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" aria-hidden="true" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索设备或主机名"
              className="w-full rounded-lg border-0 bg-slate-50 py-2.5 pl-9 pr-3 text-sm text-slate-950 ring-1 ring-inset ring-slate-200 placeholder:text-slate-400 focus:bg-white focus:ring-2 focus:ring-indigo-600"
            />
          </div>
          <div className="flex max-w-full gap-1 overflow-x-auto rounded-lg bg-slate-100 p-1">
            {filters.map((filter) => (
              <button
                key={filter.value}
                onClick={() => setStatus(filter.value)}
                className={cn('min-h-11 whitespace-nowrap rounded-md px-3 py-1.5 text-xs font-semibold transition', status === filter.value ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500 hover:text-slate-800')}
              >
                {filter.label}
              </button>
            ))}
          </div>
        </div>

        {loading ? (
          <div className="flex min-h-80 items-center justify-center"><Spinner /></div>
        ) : filteredDevices.length === 0 ? (
          <EmptyState
            icon={Laptop}
            title={devices.length === 0 ? '还没有注册设备' : '没有匹配的设备'}
            description={devices.length === 0 ? '创建加入凭证并运行 Agent connect，设备会出现在这里。' : '尝试调整搜索关键词或状态筛选。'}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-100">
              <thead className="bg-slate-50/70">
                <tr>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 sm:px-6">设备</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">状态</th>
                  <th className="hidden px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 md:table-cell">版本</th>
                  <th className="hidden px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 md:table-cell">最近心跳</th>
                  <th className="relative px-5 py-3 sm:px-6"><span className="sr-only">操作</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {filteredDevices.map((device) => (
                  <tr key={device.id} className="group transition hover:bg-slate-50/70">
                    <td className="whitespace-nowrap px-5 py-4 sm:px-6">
                      <button className="flex min-h-11 items-center gap-3 text-left" onClick={() => void openDetails(device)}>
                        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-slate-500"><Laptop className="h-4 w-4" aria-hidden="true" /></div>
                        <div>
                          <div className="text-sm font-semibold text-slate-900 group-hover:text-indigo-700">{device.name}</div>
                          <div className="mt-1 text-xs text-slate-500">{device.hostname} · {device.os}/{device.arch}</div>
                        </div>
                      </button>
                    </td>
                    <td className="whitespace-nowrap px-5 py-4"><Badge className={statusStyles[device.status]}>{statusLabels[device.status]}</Badge></td>
                    <td className="hidden whitespace-nowrap px-5 py-4 text-xs text-slate-500 md:table-cell">Agent {device.agentVersion}<br />Tailcat {device.tailcatVersion}</td>
                    <td className="hidden whitespace-nowrap px-5 py-4 md:table-cell"><div className="text-xs font-medium text-slate-700">{formatRelativeDate(device.lastSeenAt)}</div><div className="mt-1 text-[11px] text-slate-400">{formatDate(device.lastSeenAt)}</div></td>
                    <td className="whitespace-nowrap px-5 py-4 text-right sm:px-6">
                      <button className="flex min-h-11 min-w-11 items-center justify-center rounded-lg p-2 text-slate-400 transition hover:bg-slate-100 hover:text-slate-700" onClick={() => void openDetails(device)} aria-label={`查看 ${device.name} 详情`}><ChevronRight className="h-4 w-4" aria-hidden="true" /></button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal open={selectedDevice !== null} onClose={() => { setSelectedDevice(null); setSelectedVirtualNetworks([]) }} title={selectedDevice?.name ?? '设备详情'} description={selectedDevice ? `${selectedDevice.hostname} · ${selectedDevice.os}/${selectedDevice.arch}` : undefined} size="lg">
        {selectedDevice && (
          <div className="space-y-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <Badge className={statusStyles[selectedDevice.status]}>{statusLabels[selectedDevice.status]}</Badge>
              <div className="flex gap-2">
                {selectedDevice.status === 'PENDING' && <Button loading={actionId === selectedDevice.id} onClick={() => setConfirm({ type: 'approve', device: selectedDevice })}><ShieldCheck className="h-4 w-4" aria-hidden="true" />批准设备</Button>}
                {selectedDevice.status !== 'DISABLED' && <Button variant="danger" loading={actionId === selectedDevice.id} onClick={() => setConfirm({ type: 'disable', device: selectedDevice })}><XCircle className="h-4 w-4" aria-hidden="true" />禁用设备</Button>}
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <DetailItem label="设备 ID" value={selectedDevice.id} copy={() => void copyValue(selectedDevice.id, '设备 ID')} />
              <DetailItem label="Mesh Network" value={selectedDevice.networkId} />
              <DetailItem label="Agent 版本" value={selectedDevice.agentVersion} />
              <DetailItem label="Tailcat 版本" value={selectedDevice.tailcatVersion} />
              <DetailItem label="最近心跳" value={formatDate(selectedDevice.lastSeenAt)} />
              <DetailItem label="Desired Revision" value={String(selectedDevice.desiredRevision)} />
            </div>

            <div className="rounded-xl bg-slate-50 p-4 ring-1 ring-slate-100">
              <div className="flex items-center justify-between gap-3">
                <p className="text-xs font-bold uppercase tracking-[0.16em] text-slate-400">Client public key</p>
                {selectedDevice.clientPublicKey && <button onClick={() => void copyValue(selectedDevice.clientPublicKey ?? '', 'Client 公钥')} className="inline-flex min-h-11 items-center gap-1 text-xs font-semibold text-indigo-600 hover:text-indigo-500"><Copy className="h-3.5 w-3.5" aria-hidden="true" />复制</button>}
              </div>
              <p className="mt-2 break-all font-mono text-xs leading-5 text-slate-600">{selectedDevice.clientPublicKey ?? '尚未上报'}</p>
            </div>
            <div className="rounded-xl bg-slate-50 p-4 ring-1 ring-slate-100">
              <p className="text-xs font-bold uppercase tracking-[0.16em] text-slate-400">Server ConnBlob hash</p>
              <p className="mt-2 break-all font-mono text-xs leading-5 text-slate-600">{selectedDevice.serverConnBlobHash ?? '尚未上报'}</p>
            </div>
            <div className="rounded-xl bg-slate-50 p-4 ring-1 ring-slate-100">
              <div className="flex items-center gap-2">
                <Network className="h-4 w-4 text-indigo-500" aria-hidden="true" />
                <p className="text-xs font-bold uppercase tracking-[0.16em] text-slate-400">Virtual Networks</p>
              </div>
              {selectedVirtualNetworks.length === 0 ? <p className="mt-3 text-sm text-slate-500">尚未加入 Virtual Network。</p> : <div className="mt-3 space-y-2">{selectedVirtualNetworks.map((network) => <div key={network.networkId} className="flex items-center justify-between gap-3 rounded-lg bg-white px-3 py-3 ring-1 ring-slate-100"><div className="min-w-0"><p className="truncate text-sm font-semibold text-slate-800">{network.networkName}</p><p className="mt-1 font-mono text-xs text-slate-500">{network.cidr}</p></div><div className="shrink-0 text-right"><p className="font-mono text-sm font-semibold text-indigo-700">{network.virtualIpv4}</p><p className={`mt-1 text-[11px] ${network.networkEnabled && network.memberEnabled ? 'text-emerald-600' : 'text-slate-400'}`}>{network.networkEnabled && network.memberEnabled ? '已启用' : '已停用'}</p></div></div>)}</div>}
            </div>
            <div className="grid gap-3 text-xs text-slate-500 sm:grid-cols-2">
              <p>创建于：<span className="font-medium text-slate-700">{formatDate(selectedDevice.createdAt)}</span></p>
              <p>更新于：<span className="font-medium text-slate-700">{formatDate(selectedDevice.updatedAt)}</span></p>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={confirm?.type === 'approve' ? '批准这台设备？' : '禁用这台设备？'}
        description={confirm?.device.name}
      >
        <div className="space-y-5">
          <div className={cn('rounded-xl p-4 text-sm leading-6 ring-1', confirm?.type === 'approve' ? 'bg-emerald-50 text-emerald-800 ring-emerald-200' : 'bg-rose-50 text-rose-800 ring-rose-200')}>
            {confirm?.type === 'approve' ? '批准后，设备可以进入 Mesh 控制流程，并在下一次心跳后上线。' : '禁用后，设备将无法继续使用控制面凭证。此操作不会删除设备记录。'}
          </div>
          <div className="flex justify-end gap-3"><Button variant="secondary" onClick={() => setConfirm(null)}>取消</Button><Button variant={confirm?.type === 'approve' ? 'primary' : 'danger'} onClick={() => void performAction()}>{confirm?.type === 'approve' ? <><Check className="h-4 w-4" aria-hidden="true" />确认批准</> : <><ShieldAlert className="h-4 w-4" aria-hidden="true" />确认禁用</>}</Button></div>
        </div>
      </Modal>
    </>
  )
}

function DetailItem({ label, value, copy }: { label: string; value: string; copy?: () => void }) {
  return (
    <div className="rounded-xl border border-slate-100 px-4 py-3">
      <p className="text-xs text-slate-400">{label}</p>
      <div className="mt-1 flex items-center gap-2"><p className="min-w-0 truncate text-sm font-medium text-slate-800" title={value}>{shorten(value, 14, 8)}</p>{copy && <button onClick={copy} className="flex min-h-11 min-w-11 shrink-0 items-center justify-center text-slate-400 hover:text-indigo-600" aria-label={`复制${label}`}><Copy className="h-3.5 w-3.5" aria-hidden="true" /></button>}</div>
    </div>
  )
}
