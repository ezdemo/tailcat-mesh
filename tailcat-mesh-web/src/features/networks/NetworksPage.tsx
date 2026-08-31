import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Network, Plus, RefreshCw, Trash2, UserMinus, UserPlus } from 'lucide-react'
import type { TailcatMeshApi } from '../../api/client'
import { errorMessage, isUnauthorized } from '../../lib/errors'
import { formatDate } from '../../lib/format'
import type { Device, MeshNetwork, NetworkPeerPath } from '../../types'
import { Badge, Button, Card, EmptyState, LoadingState, Modal, Notice, PageHeader } from '../../components/ui'

const fieldClass = 'mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600'

export function NetworksPage({ api, onUnauthorized }: { api: TailcatMeshApi; onUnauthorized: () => void }) {
  const [networks, setNetworks] = useState<MeshNetwork[]>([])
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [creating, setCreating] = useState(false)
  const [action, setAction] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [name, setName] = useState('')
  const [cidr, setCidr] = useState('')
  const [selectedDevices, setSelectedDevices] = useState<Record<string, string>>({})
  const [selectedIps, setSelectedIps] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function load(showRefresh = false) {
    setError(null)
    showRefresh ? setRefreshing(true) : setLoading(true)
    try {
      const [nextNetworks, nextDevices] = await Promise.all([api.listNetworks(), api.listDevices()])
      setNetworks(nextNetworks)
      setDevices(nextDevices)
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

  async function createNetwork(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setCreating(true)
    setError(null)
    try {
      await api.createNetwork({ name: name.trim(), ...(cidr.trim() ? { cidr: cidr.trim() } : {}) })
      setName('')
      setCidr('')
      setCreateOpen(false)
      setNotice('Virtual Network 已创建。现在可以选择已批准设备加入。')
      await load(true)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setCreating(false)
    }
  }

  async function addMember(network: MeshNetwork) {
    const deviceId = selectedDevices[network.id]
    if (!deviceId) return
    const key = `add:${network.id}:${deviceId}`
    setAction(key)
    setError(null)
    try {
      await api.addNetworkMember(network.id, {
        deviceId,
        ...(selectedIps[network.id]?.trim() ? { virtualIpv4: selectedIps[network.id].trim() } : {}),
      })
      setSelectedDevices((current) => ({ ...current, [network.id]: '' }))
      setSelectedIps((current) => ({ ...current, [network.id]: '' }))
      setNotice('设备已加入 Network，Virtual IPv4 已固定。')
      await load(true)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setAction(null)
    }
  }

  async function removeMember(network: MeshNetwork, deviceId: string) {
    const key = `remove:${network.id}:${deviceId}`
    setAction(key)
    setError(null)
    try {
      await api.removeNetworkMember(network.id, deviceId)
      setNotice('设备已移除；后续 M7 runtime reconcile 会撤销该 Network 的访问。')
      await load(true)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setAction(null)
    }
  }

  async function toggleNetwork(network: MeshNetwork) {
    const key = `toggle:${network.id}`
    setAction(key)
    setError(null)
    try {
      await api.updateNetwork(network.id, { name: network.name, cidr: network.cidr, enabled: !network.enabled })
      setNotice(network.enabled ? 'Network 已停用。' : 'Network 已启用。')
      await load(true)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setAction(null)
    }
  }

  async function deleteNetwork(network: MeshNetwork) {
    if (!window.confirm(`确定删除 Network「${network.name}」吗？`)) return
    const key = `delete:${network.id}`
    setAction(key)
    setError(null)
    try {
      await api.deleteNetwork(network.id)
      setNotice('Network 已删除。')
      await load(true)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setAction(null)
    }
  }

  const approvedDevices = useMemo(() => devices.filter((device) => device.status === 'ONLINE' || device.status === 'OFFLINE'), [devices])

  function availableDevices(network: MeshNetwork) {
    const memberIds = new Set(network.members.filter((member) => member.enabled).map((member) => member.deviceId))
    return approvedDevices.filter((device) => !memberIds.has(device.id))
  }

  return (
    <>
      <PageHeader
        eyebrow="Virtual LAN · M7.1"
        title="Networks"
        description="创建 TCP-first Virtual LAN，给同一 Network 的已批准设备分配稳定 Virtual IPv4，并查看成员在线状态与 Peer 路径。"
        actions={<><Button variant="secondary" loading={refreshing} onClick={() => void load(true)}><RefreshCw className="h-4 w-4" aria-hidden="true" />刷新</Button><Button onClick={() => setCreateOpen(true)}><Plus className="h-4 w-4" aria-hidden="true" />创建 Network</Button></>}
      />

      <div className="space-y-4">
        {error && <Notice tone="error" title="操作失败" message={error} onClose={() => setError(null)} />}
        {notice && <Notice tone="success" message={notice} onClose={() => setNotice(null)} />}
      </div>

      {loading ? <LoadingState className="mt-6 min-h-[24rem]" rows={4} /> : networks.length === 0 ? (
        <Card className="mt-6"><EmptyState icon={Network} title="还没有 Virtual Network" description="创建一个 Network，然后从已批准设备中选择成员。CIDR 留空时将从 10.77.0.0/16 自动分配 /24 子网。" action={<Button onClick={() => setCreateOpen(true)}><Plus className="h-4 w-4" aria-hidden="true" />创建第一个 Network</Button>} /></Card>
      ) : (
        <div className="mt-6 grid gap-5 xl:grid-cols-2">
          {networks.map((network) => {
            const available = availableDevices(network)
            const activeMembers = network.members.filter((member) => member.enabled)
            const peerPaths = network.peerPaths ?? []
            return (
              <Card key={network.id} className="overflow-hidden">
                <div className="border-b border-slate-100 px-5 py-5 sm:px-6">
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex min-w-0 items-start gap-3">
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600"><Network className="h-5 w-5" aria-hidden="true" /></div>
                      <div className="min-w-0"><h2 className="truncate text-base font-semibold text-slate-950">{network.name}</h2><p className="mt-1 font-mono text-xs text-slate-500">{network.cidr}</p></div>
                    </div>
                    <Badge className={network.enabled ? 'bg-emerald-50 text-emerald-700 ring-emerald-600/20' : 'bg-slate-100 text-slate-500 ring-slate-500/20'}>{network.enabled ? '已启用' : '已停用'}</Badge>
                  </div>
                  <div className="mt-4 flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-500"><span>{activeMembers.length} 个活动成员</span><span>slug: {network.slug}</span><span>创建于 {formatDate(network.createdAt)}</span></div>
                </div>

                <div className="px-5 py-5 sm:px-6">
                  {activeMembers.length === 0 ? <p className="rounded-xl bg-slate-50 px-4 py-5 text-center text-sm text-slate-500">还没有设备加入这个 Network。</p> : <div className="space-y-2">{network.members.map((member) => <div key={member.id} className={`flex items-center justify-between gap-3 rounded-xl px-3 py-3 ring-1 ${member.enabled ? 'bg-white ring-slate-100' : 'bg-slate-50 text-slate-400 ring-slate-100'}`}><div className="min-w-0"><p className={`truncate text-sm font-semibold ${member.enabled ? 'text-slate-800' : 'text-slate-400'}`}>{member.deviceName}</p><p className="mt-1 text-xs text-slate-500">{member.hostname} · {memberStatusLabel(member.deviceStatus)} · <span className="font-mono font-semibold">{member.virtualIpv4}</span></p>{member.enabled && <p className="mt-1 text-[11px] text-slate-400">{memberPathSummary(peerPaths, member.deviceId)}</p>}</div>{member.enabled ? <Button variant="ghost" className="shrink-0 px-2 text-rose-600 hover:bg-rose-50 hover:text-rose-700" loading={action === `remove:${network.id}:${member.deviceId}`} onClick={() => void removeMember(network, member.deviceId)}><UserMinus className="h-4 w-4" aria-hidden="true" /><span className="hidden sm:inline">移除</span></Button> : <span className="shrink-0 text-xs text-slate-400">已移除</span>}</div>)}</div>}

                  {peerPaths.length > 0 && <div className="mt-5 rounded-xl border border-slate-100 bg-white p-3"><div className="mb-2 flex items-center justify-between"><p className="text-xs font-bold uppercase tracking-[0.14em] text-slate-400">Peer 路径</p><span className="text-[11px] text-slate-400">Direct / DERP</span></div><div className="space-y-2">{peerPaths.map((path) => <div key={`${path.sourceDeviceId}-${path.peerDeviceId}`} className="flex items-center justify-between gap-3 text-xs"><span className="min-w-0 truncate text-slate-600">{path.sourceDeviceName} → {path.peerDeviceName}</span><span className={path.pathType === 'DIRECT' ? 'shrink-0 font-semibold text-indigo-600' : path.pathType === 'DERP' ? 'shrink-0 font-semibold text-sky-600' : 'shrink-0 text-slate-400'}>{pathLabel(path)}</span></div>)}</div></div>}

                  <div className="mt-5 rounded-xl bg-slate-50 p-3 ring-1 ring-slate-100">
                    <div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase tracking-[0.14em] text-slate-400"><UserPlus className="h-4 w-4" aria-hidden="true" />添加设备</div>
                    {available.length === 0 ? <p className="text-xs text-slate-500">没有可加入的已批准设备。</p> : <div className="grid gap-2 sm:grid-cols-[1fr_9rem_auto]"><select aria-label={`选择 ${network.name} 的设备`} value={selectedDevices[network.id] ?? ''} onChange={(event) => setSelectedDevices((current) => ({ ...current, [network.id]: event.target.value }))} className={fieldClass}><option value="">选择设备…</option>{available.map((device) => <option key={device.id} value={device.id}>{device.name} · {device.status}</option>)}</select><input aria-label={`${network.name} 的手动 Virtual IPv4`} placeholder="自动 IP" value={selectedIps[network.id] ?? ''} onChange={(event) => setSelectedIps((current) => ({ ...current, [network.id]: event.target.value }))} className={fieldClass} /><Button className="mt-2 sm:mt-0" disabled={!selectedDevices[network.id]} loading={action === `add:${network.id}:${selectedDevices[network.id]}`} onClick={() => void addMember(network)}>加入</Button></div>}
                  </div>
                </div>

                <div className="flex items-center justify-between border-t border-slate-100 bg-slate-50/60 px-5 py-3 sm:px-6"><span className="text-xs text-slate-400">更新于 {formatDate(network.updatedAt)}</span><div className="flex gap-1"><Button variant="ghost" className="px-2 text-slate-600" loading={action === `toggle:${network.id}`} onClick={() => void toggleNetwork(network)}>{network.enabled ? '停用' : '启用'}</Button><Button variant="ghost" className="px-2 text-rose-600 hover:bg-rose-50 hover:text-rose-700" loading={action === `delete:${network.id}`} onClick={() => void deleteNetwork(network)}><Trash2 className="h-4 w-4" aria-hidden="true" /><span className="hidden sm:inline">删除</span></Button></div></div>
              </Card>
            )
          })}
        </div>
      )}

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="创建 Virtual Network" description="CIDR 留空时，从 10.77.0.0/16 自动选择不冲突的 /24 子网。">
        <form className="space-y-5" onSubmit={createNetwork}><label className="block"><span className="text-sm font-semibold text-slate-700">名称</span><input required maxLength={128} value={name} onChange={(event) => setName(event.target.value)} className={fieldClass} placeholder="例如：home" /></label><label className="block"><span className="text-sm font-semibold text-slate-700">CIDR（可选）</span><input value={cidr} onChange={(event) => setCidr(event.target.value)} className={fieldClass} placeholder="例如：10.77.10.0/24" /><span className="mt-2 block text-xs text-slate-400">Server 会校验本机网段和已有 Virtual Network 是否重叠。</span></label><div className="flex justify-end gap-3"><Button type="button" variant="secondary" onClick={() => setCreateOpen(false)}>取消</Button><Button type="submit" loading={creating}><Plus className="h-4 w-4" aria-hidden="true" />创建</Button></div></form>
      </Modal>
    </>
  )
}

function memberStatusLabel(status: Device['status']): string {
  return status === 'ONLINE' ? '在线' : status === 'OFFLINE' ? '离线' : status === 'DISABLED' ? '已禁用' : '待审批'
}

function memberPathSummary(paths: NetworkPeerPath[], deviceId: string): string {
  const related = paths.filter((path) => path.sourceDeviceId === deviceId || path.peerDeviceId === deviceId)
  if (related.length === 0) return 'Peer 路径待上报'
  const direct = related.filter((path) => path.pathType === 'DIRECT').length
  const derp = related.filter((path) => path.pathType === 'DERP').length
  if (direct === 0 && derp === 0) return 'Peer 路径不可用或未知'
  return `${direct} Direct · ${derp} DERP`
}

function pathLabel(path: NetworkPeerPath): string {
  if (path.pathType === 'DIRECT') return 'Direct'
  if (path.pathType === 'DERP') return path.derpRegion ? `DERP · ${path.derpRegion}` : 'DERP'
  return path.pathType === 'OFFLINE' ? 'Offline' : 'Unknown'
}
