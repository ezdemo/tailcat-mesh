import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Pencil, Plus, RefreshCw, Server, Trash2, XCircle } from 'lucide-react'
import type { TailcatMeshApi } from '../../api/client'
import { errorMessage, isUnauthorized } from '../../lib/errors'
import { formatDate, shorten } from '../../lib/format'
import type { Device, Service, ServiceRequest, ServiceStatus } from '../../types'
import { Badge, Button, Card, EmptyState, Modal, Notice, PageHeader, Spinner } from '../../components/ui'

const serviceStatusLabels: Record<ServiceStatus, string> = {
  STARTING: '启动中',
  READY: '就绪',
  FAILED: '失败',
  STOPPED: '已停止',
}

const serviceStatusStyles: Record<ServiceStatus, string> = {
  STARTING: 'bg-amber-50 text-amber-700 ring-amber-600/20',
  READY: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  FAILED: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  STOPPED: 'bg-slate-100 text-slate-600 ring-slate-500/20',
}

interface ServiceFormState {
  deviceId: string
  name: string
  targetHost: string
  targetPort: string
  enabled: boolean
}

const emptyForm: ServiceFormState = {
  deviceId: '',
  name: '',
  targetHost: '',
  targetPort: '80',
  enabled: true,
}

export function ServicesPage({ api, onUnauthorized }: { api: TailcatMeshApi; onUnauthorized: () => void }) {
  const [services, setServices] = useState<Service[]>([])
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Service | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Service | null>(null)
  const [form, setForm] = useState<ServiceFormState>(emptyForm)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [lastLoadedAt, setLastLoadedAt] = useState<string | null>(null)

  async function load(showRefresh = false) {
    setError(null)
    showRefresh ? setRefreshing(true) : setLoading(true)
    try {
      const [nextServices, nextDevices] = await Promise.all([api.listServices(), api.listDevices()])
      setServices(nextServices)
      setDevices(nextDevices)
      setLastLoadedAt(new Date().toISOString())
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

  const deviceNames = useMemo(() => new Map(devices.map((device) => [device.id, device.name])), [devices])
  const usableDevices = useMemo(() => devices.filter((device) => device.status !== 'DISABLED'), [devices])
  const readyCount = useMemo(() => services.filter((service) => service.status === 'READY' && service.enabled).length, [services])

  function openCreate() {
    setEditing(null)
    setForm({ ...emptyForm, deviceId: usableDevices[0]?.id ?? '' })
    setError(null)
    setFormOpen(true)
  }

  function openEdit(service: Service) {
    setEditing(service)
    setForm({
      deviceId: service.deviceId,
      name: service.name,
      targetHost: service.targetHost,
      targetPort: String(service.targetPort),
      enabled: service.enabled,
    })
    setError(null)
    setFormOpen(true)
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    const request: ServiceRequest = {
      deviceId: form.deviceId,
      name: form.name.trim(),
      protocol: 'TCP',
      targetHost: form.targetHost.trim(),
      targetPort: Number(form.targetPort),
      enabled: form.enabled,
    }
    try {
      const saved = editing
        ? await api.updateService(editing.id, request)
        : await api.createService(request)
      setServices((current) => editing
        ? current.map((service) => service.id === saved.id ? saved : service)
        : [saved, ...current])
      setFormOpen(false)
      setNotice(editing ? '服务配置已更新，Agent 将在同步后重建桥接。' : '服务已创建，Agent 将在同步后启动桥接。')
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setSaving(false)
    }
  }

  async function remove() {
    if (!deleteTarget) return
    setDeletingId(deleteTarget.id)
    setError(null)
    try {
      await api.deleteService(deleteTarget.id)
      setServices((current) => current.filter((service) => service.id !== deleteTarget.id))
      setDeleteTarget(null)
      setNotice('服务已删除。')
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setDeletingId(null)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="Published services"
        title="服务"
        description="把设备所在网络里的 TCP 服务发布到 Mesh。Agent 会在本机创建 loopback bridge，并由 Tailcat 对外提供访问。"
        actions={<><Button variant="secondary" loading={refreshing} onClick={() => void load(true)}><RefreshCw className="h-4 w-4" aria-hidden="true" />刷新</Button><Button onClick={openCreate} disabled={usableDevices.length === 0}><Plus className="h-4 w-4" aria-hidden="true" />发布服务</Button></>}
      />

      <div className="space-y-4">
        {error && <Notice tone="error" title="操作失败" message={error} onClose={() => setError(null)} />}
        {notice && <Notice tone="success" message={notice} onClose={() => setNotice(null)} />}
      </div>

      <div className="mt-6 flex items-center gap-3 rounded-2xl bg-indigo-50 px-5 py-4 text-sm text-indigo-800 ring-1 ring-indigo-100">
        <Server className="h-5 w-5 shrink-0 text-indigo-600" aria-hidden="true" />
        <p>当前共有 <span className="font-semibold">{services.length}</span> 个服务，其中 <span className="font-semibold">{readyCount}</span> 个 Agent bridge 已就绪。Bridge 端口由 Agent 动态分配，只绑定在 127.0.0.1。</p>
      </div>

      <Card className="mt-6 overflow-hidden">
        {loading ? (
          <div className="flex min-h-80 items-center justify-center"><Spinner /></div>
        ) : services.length === 0 ? (
          <EmptyState icon={Server} title="还没有发布服务" description="选择一台已注册设备，填写它能访问的 TCP 目标地址。" action={<Button onClick={openCreate} disabled={usableDevices.length === 0}><Plus className="h-4 w-4" aria-hidden="true" />发布第一个服务</Button>} />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-100">
              <thead className="bg-slate-50/70">
                <tr>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 sm:px-6">名称</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">设备</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">目标</th>
                  <th className="hidden px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 md:table-cell">Bridge Port</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">状态</th>
                  <th className="relative px-5 py-3 sm:px-6"><span className="sr-only">操作</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {services.map((service) => (
                  <tr key={service.id} className="transition hover:bg-slate-50/70">
                    <td className="whitespace-nowrap px-5 py-4 sm:px-6">
                      <div className="flex items-center gap-3">
                        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600"><Server className="h-4 w-4" aria-hidden="true" /></div>
                        <div><p className="text-sm font-semibold text-slate-900">{service.name}</p><p className="mt-1 font-mono text-[11px] text-slate-400">{shorten(service.id, 8, 5)}</p></div>
                      </div>
                    </td>
                    <td className="whitespace-nowrap px-5 py-4 text-sm text-slate-700">{deviceNames.get(service.deviceId) ?? shorten(service.deviceId)}</td>
                    <td className="whitespace-nowrap px-5 py-4"><span className="font-mono text-xs text-slate-700">{service.targetHost}:{service.targetPort}</span><p className="mt-1 text-[11px] text-slate-400">{service.protocol}</p></td>
                    <td className="hidden whitespace-nowrap px-5 py-4 font-mono text-xs text-slate-600 md:table-cell">{service.bridgePort ? `127.0.0.1:${service.bridgePort}` : '—'}</td>
                    <td className="whitespace-nowrap px-5 py-4"><Badge className={serviceStatusStyles[service.status]}>{service.enabled ? serviceStatusLabels[service.status] : '已禁用'}</Badge>{service.lastError && <p className="mt-1 max-w-xs truncate text-[11px] text-rose-600" title={service.lastError}>{service.lastError}</p>}</td>
                    <td className="whitespace-nowrap px-5 py-4 text-right sm:px-6"><div className="flex justify-end gap-1"><Button variant="ghost" className="px-2 text-slate-600" onClick={() => openEdit(service)}><Pencil className="h-4 w-4" aria-hidden="true" /><span className="hidden sm:inline">编辑</span></Button><Button variant="ghost" className="px-2 text-rose-600 hover:bg-rose-50 hover:text-rose-700" onClick={() => setDeleteTarget(service)}><Trash2 className="h-4 w-4" aria-hidden="true" /><span className="hidden sm:inline">删除</span></Button></div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal open={formOpen} onClose={() => setFormOpen(false)} title={editing ? '编辑服务' : '发布 TCP 服务'} description="目标地址由该设备所在网络访问；Agent bridge 只监听本机 loopback。">
        <form className="space-y-5" onSubmit={save}>
          <label className="block"><span className="text-sm font-semibold text-slate-700">服务名称</span><input required maxLength={255} value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="例如 NAS Web" className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600" /></label>
          <label className="block"><span className="text-sm font-semibold text-slate-700">发布设备</span><select required value={form.deviceId} onChange={(event) => setForm({ ...form, deviceId: event.target.value })} className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600"><option value="" disabled>选择设备</option>{usableDevices.map((device) => <option key={device.id} value={device.id}>{device.name} · {device.hostname}</option>)}</select><span className="mt-2 block text-xs text-slate-400">禁用设备不能发布新服务。</span></label>
          <div className="grid gap-4 sm:grid-cols-[1fr_10rem]">
            <label className="block"><span className="text-sm font-semibold text-slate-700">目标主机</span><input required maxLength={255} value={form.targetHost} onChange={(event) => setForm({ ...form, targetHost: event.target.value })} placeholder="127.0.0.1 或 192.168.1.20" className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600" /></label>
            <label className="block"><span className="text-sm font-semibold text-slate-700">目标端口</span><input required min="1" max="65535" type="number" value={form.targetPort} onChange={(event) => setForm({ ...form, targetPort: event.target.value })} className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600" /></label>
          </div>
          <label className="flex items-center gap-3 rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-700 ring-1 ring-slate-100"><input type="checkbox" checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-600" /><span><span className="font-semibold">启用服务</span><span className="mt-0.5 block text-xs text-slate-400">关闭后 Agent 会停止 bridge，但保留配置。</span></span></label>
          <div className="flex justify-end gap-3"><Button type="button" variant="secondary" onClick={() => setFormOpen(false)}>取消</Button><Button type="submit" loading={saving} disabled={!form.deviceId}><Check className="h-4 w-4" aria-hidden="true" />{editing ? '保存修改' : '发布服务'}</Button></div>
        </form>
      </Modal>

      <Modal open={deleteTarget !== null} onClose={() => setDeleteTarget(null)} title="删除这个服务？" description={deleteTarget?.name}>
        <div className="space-y-5"><div className="flex items-start gap-3 rounded-xl bg-rose-50 p-4 text-sm leading-6 text-rose-800 ring-1 ring-rose-200"><XCircle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" /><p>删除后，Agent 会停止对应 bridge，远端将不能再通过这个服务访问目标地址。此操作不可撤销。</p></div><div className="flex justify-end gap-3"><Button variant="secondary" onClick={() => setDeleteTarget(null)}>取消</Button><Button variant="danger" loading={deletingId === deleteTarget?.id} onClick={() => void remove()}><Trash2 className="h-4 w-4" aria-hidden="true" />确认删除</Button></div></div>
      </Modal>

      {services.length > 0 && lastLoadedAt && <p className="mt-4 text-xs text-slate-400">最后一次列表刷新：{formatDate(lastLoadedAt)}。运行态来自 Agent 上报，配置变更可能有约 2 秒同步延迟。</p>}
    </>
  )
}
