import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { ArrowRightLeft, Check, Pencil, Plus, RefreshCw, Trash2, XCircle } from 'lucide-react'
import type { TailcatMeshApi } from '../../api/client'
import { errorMessage, isUnauthorized } from '../../lib/errors'
import { formatDate, shorten } from '../../lib/format'
import type { Device, Forward, ForwardRequest, ForwardStatus, Service } from '../../types'
import { Badge, Button, Card, EmptyState, Modal, Notice, PageHeader, Spinner } from '../../components/ui'

const statusLabels: Record<ForwardStatus, string> = {
  STARTING: '启动中',
  READY: '就绪',
  ERROR: '错误',
  STOPPED: '已停止',
}

const statusStyles: Record<ForwardStatus, string> = {
  STARTING: 'bg-amber-50 text-amber-700 ring-amber-600/20',
  READY: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  ERROR: 'bg-rose-50 text-rose-700 ring-rose-600/20',
  STOPPED: 'bg-slate-100 text-slate-600 ring-slate-500/20',
}

interface ForwardFormState {
  sourceDeviceId: string
  remoteServiceId: string
  name: string
  localBindPort: string
  enabled: boolean
}

const emptyForm: ForwardFormState = {
  sourceDeviceId: '',
  remoteServiceId: '',
  name: '',
  localBindPort: '18080',
  enabled: true,
}

export function ForwardsPage({ api, onUnauthorized }: { api: TailcatMeshApi; onUnauthorized: () => void }) {
  const [forwards, setForwards] = useState<Forward[]>([])
  const [devices, setDevices] = useState<Device[]>([])
  const [services, setServices] = useState<Service[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Forward | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Forward | null>(null)
  const [form, setForm] = useState<ForwardFormState>(emptyForm)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [lastLoadedAt, setLastLoadedAt] = useState<string | null>(null)

  async function load(showRefresh = false) {
    setError(null)
    showRefresh ? setRefreshing(true) : setLoading(true)
    try {
      const [nextForwards, nextDevices, nextServices] = await Promise.all([
        api.listForwards(),
        api.listDevices(),
        api.listServices(),
      ])
      setForwards(nextForwards)
      setDevices(nextDevices)
      setServices(nextServices)
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

  const deviceById = useMemo(() => new Map(devices.map((device) => [device.id, device])), [devices])
  const serviceById = useMemo(() => new Map(services.map((service) => [service.id, service])), [services])
  const usableDevices = useMemo(() => devices.filter((device) => device.status !== 'DISABLED'), [devices])
  const sourceDevice = deviceById.get(form.sourceDeviceId)
  const availableRemoteServices = useMemo(() => services.filter((service) => {
    const remoteDevice = deviceById.get(service.deviceId)
    return sourceDevice !== undefined
      && remoteDevice !== undefined
      && remoteDevice.status !== 'DISABLED'
      && remoteDevice.networkId === sourceDevice.networkId
      && remoteDevice.id !== sourceDevice.id
  }), [deviceById, services, sourceDevice])
  const readyCount = useMemo(() => forwards.filter((forward) => forward.enabled && forward.status === 'READY').length, [forwards])

  function openCreate() {
    setEditing(null)
    setForm({ ...emptyForm, sourceDeviceId: usableDevices[0]?.id ?? '' })
    setError(null)
    setFormOpen(true)
  }

  function openEdit(forward: Forward) {
    setEditing(forward)
    setForm({
      sourceDeviceId: forward.sourceDeviceId,
      remoteServiceId: forward.remoteServiceId,
      name: forward.name,
      localBindPort: String(forward.localBindPort),
      enabled: forward.enabled,
    })
    setError(null)
    setFormOpen(true)
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    const request: ForwardRequest = {
      sourceDeviceId: form.sourceDeviceId,
      remoteServiceId: form.remoteServiceId,
      name: form.name.trim(),
      localBindHost: '127.0.0.1',
      localBindPort: Number(form.localBindPort),
      enabled: form.enabled,
    }
    try {
      const saved = editing
        ? await api.updateForward(editing.id, request)
        : await api.createForward(request)
      setForwards((current) => editing
        ? current.map((forward) => forward.id === saved.id ? saved : forward)
        : [saved, ...current])
      setFormOpen(false)
      setNotice(editing ? '转发配置已更新，Agent 将在同步后重建本地监听。' : '转发已创建，Agent 将在同步后监听本地端口。')
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
      await api.deleteForward(deleteTarget.id)
      setForwards((current) => current.filter((forward) => forward.id !== deleteTarget.id))
      setDeleteTarget(null)
      setNotice('转发已删除。')
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setDeletingId(null)
    }
  }

  function updateSourceDevice(sourceDeviceId: string) {
    setForm((current) => ({ ...current, sourceDeviceId, remoteServiceId: '' }))
  }

  return (
    <>
      <PageHeader
        eyebrow="Local forwards"
        title="本地转发"
        description="把远端设备发布的 TCP 服务映射到当前设备的本机回环端口。用户只需连接 127.0.0.1:<端口>，不需要了解 Tailcat 协议。"
        actions={<><Button variant="secondary" loading={refreshing} onClick={() => void load(true)}><RefreshCw className="h-4 w-4" aria-hidden="true" />刷新</Button><Button onClick={openCreate} disabled={usableDevices.length < 1}><Plus className="h-4 w-4" aria-hidden="true" />新建转发</Button></>}
      />

      <div className="space-y-4">
        {error && <Notice tone="error" title="操作失败" message={error} onClose={() => setError(null)} />}
        {notice && <Notice tone="success" message={notice} onClose={() => setNotice(null)} />}
      </div>

      <div className="mt-6 flex items-start gap-3 rounded-2xl bg-indigo-50 px-5 py-4 text-sm leading-6 text-indigo-800 ring-1 ring-indigo-100">
        <ArrowRightLeft className="mt-0.5 h-5 w-5 shrink-0 text-indigo-600" aria-hidden="true" />
        <p>当前共有 <span className="font-semibold">{forwards.length}</span> 个转发，其中 <span className="font-semibold">{readyCount}</span> 个本地监听已就绪。监听只绑定 127.0.0.1；配置变更通常会在约 2 秒内同步到 Agent。</p>
      </div>

      <Card className="mt-6 overflow-hidden">
        {loading ? (
          <div className="flex min-h-80 items-center justify-center"><Spinner /></div>
        ) : forwards.length === 0 ? (
          <EmptyState icon={ArrowRightLeft} title="还没有本地转发" description="先发布一个远端 TCP 服务，再把它映射到某台源设备的本地端口。" action={<Button onClick={openCreate} disabled={usableDevices.length < 1}><Plus className="h-4 w-4" aria-hidden="true" />新建第一个转发</Button>} />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-100">
              <thead className="bg-slate-50/70">
                <tr>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 sm:px-6">名称</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">源设备</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">远端服务</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">本地地址</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">状态</th>
                  <th className="relative px-5 py-3 sm:px-6"><span className="sr-only">操作</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {forwards.map((forward) => (
                  <tr key={forward.id} className="transition hover:bg-slate-50/70">
                    <td className="whitespace-nowrap px-5 py-4 sm:px-6"><div className="flex items-center gap-3"><div className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600"><ArrowRightLeft className="h-4 w-4" aria-hidden="true" /></div><div><p className="text-sm font-semibold text-slate-900">{forward.name}</p><p className="mt-1 font-mono text-[11px] text-slate-400">{shorten(forward.id, 8, 5)}</p></div></div></td>
                    <td className="whitespace-nowrap px-5 py-4 text-sm text-slate-700">{forward.sourceDeviceName || deviceById.get(forward.sourceDeviceId)?.name || shorten(forward.sourceDeviceId)}</td>
                    <td className="whitespace-nowrap px-5 py-4"><p className="text-sm text-slate-700">{forward.remoteServiceName || serviceById.get(forward.remoteServiceId)?.name || 'unknown'}</p><p className="mt-1 text-xs text-slate-400">{forward.remoteDeviceName || (forward.remoteDeviceId ? deviceById.get(forward.remoteDeviceId)?.name : undefined) || '远端设备未知'}</p></td>
                    <td className="whitespace-nowrap px-5 py-4 font-mono text-xs text-slate-700">{forward.localBindHost}:{forward.localBindPort}</td>
                    <td className="whitespace-nowrap px-5 py-4"><Badge className={statusStyles[forward.status]}>{forward.enabled ? statusLabels[forward.status] : '已禁用'}</Badge>{forward.errorCode && <p className="mt-1 text-[11px] font-medium text-rose-600">{forward.errorCode}</p>}{forward.lastError && <p className="mt-1 max-w-xs truncate text-[11px] text-rose-600" title={forward.lastError}>{forward.lastError}</p>}</td>
                    <td className="whitespace-nowrap px-5 py-4 text-right sm:px-6"><div className="flex justify-end gap-1"><Button variant="ghost" className="px-2 text-slate-600" onClick={() => openEdit(forward)}><Pencil className="h-4 w-4" aria-hidden="true" /><span className="hidden sm:inline">编辑</span></Button><Button variant="ghost" className="px-2 text-rose-600 hover:bg-rose-50 hover:text-rose-700" onClick={() => setDeleteTarget(forward)}><Trash2 className="h-4 w-4" aria-hidden="true" /><span className="hidden sm:inline">删除</span></Button></div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal open={formOpen} onClose={() => setFormOpen(false)} title={editing ? '编辑本地转发' : '新建本地转发'} description="本地端口固定绑定在 127.0.0.1，不会自动改端口。">
        <form className="space-y-5" onSubmit={save}>
          <label className="block"><span className="text-sm font-semibold text-slate-700">转发名称</span><input required maxLength={255} value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="例如办公网 NAS" className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600" /></label>
          <label className="block"><span className="text-sm font-semibold text-slate-700">源设备</span><select required value={form.sourceDeviceId} onChange={(event) => updateSourceDevice(event.target.value)} className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600"><option value="" disabled>选择源设备</option>{usableDevices.map((device) => <option key={device.id} value={device.id}>{device.name} · {device.hostname}</option>)}</select><span className="mt-2 block text-xs text-slate-400">用户会在这台设备上访问本地地址。</span></label>
          <label className="block"><span className="text-sm font-semibold text-slate-700">远端 TCP 服务</span><select required value={form.remoteServiceId} onChange={(event) => setForm({ ...form, remoteServiceId: event.target.value })} className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600"><option value="" disabled>选择同一 Mesh 中的远端服务</option>{availableRemoteServices.map((service) => <option key={service.id} value={service.id}>{service.name} · {deviceById.get(service.deviceId)?.name ?? shorten(service.deviceId)} · {service.targetHost}:{service.targetPort}</option>)}</select><span className="mt-2 block text-xs text-slate-400">不能选择源设备自己的服务；远端服务需要由它所属 Agent 提供。</span></label>
          <div className="grid gap-4 sm:grid-cols-[1fr_10rem]"><label className="block"><span className="text-sm font-semibold text-slate-700">本地绑定地址</span><input value="127.0.0.1" readOnly className="mt-2 block w-full rounded-xl border-0 bg-slate-100 px-4 py-3 font-mono text-sm text-slate-600 ring-1 ring-inset ring-slate-200" /><span className="mt-2 block text-xs text-slate-400">仅允许回环访问。</span></label><label className="block"><span className="text-sm font-semibold text-slate-700">本地端口</span><input required min="1" max="65535" type="number" value={form.localBindPort} onChange={(event) => setForm({ ...form, localBindPort: event.target.value })} className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600" /></label></div>
          <label className="flex items-center gap-3 rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-700 ring-1 ring-slate-100"><input type="checkbox" checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-600" /><span><span className="font-semibold">启用转发</span><span className="mt-0.5 block text-xs text-slate-400">关闭后保留配置，但 Agent 会停止本地监听。</span></span></label>
          <div className="flex justify-end gap-3"><Button type="button" variant="secondary" onClick={() => setFormOpen(false)}>取消</Button><Button type="submit" loading={saving} disabled={!form.sourceDeviceId || !form.remoteServiceId}><Check className="h-4 w-4" aria-hidden="true" />{editing ? '保存修改' : '创建转发'}</Button></div>
        </form>
      </Modal>

      <Modal open={deleteTarget !== null} onClose={() => setDeleteTarget(null)} title="删除这个转发？" description={deleteTarget?.name}>
        <div className="space-y-5"><div className="flex items-start gap-3 rounded-xl bg-rose-50 p-4 text-sm leading-6 text-rose-800 ring-1 ring-rose-200"><XCircle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" /><p>删除后，源设备上的本地监听会停止，用户将不能再通过该地址访问远端服务。此操作不可撤销。</p></div><div className="flex justify-end gap-3"><Button variant="secondary" onClick={() => setDeleteTarget(null)}>取消</Button><Button variant="danger" loading={deletingId === deleteTarget?.id} onClick={() => void remove()}><Trash2 className="h-4 w-4" aria-hidden="true" />确认删除</Button></div></div>
      </Modal>

      {forwards.length > 0 && lastLoadedAt && <p className="mt-4 text-xs text-slate-400">最后一次列表刷新：{formatDate(lastLoadedAt)}。运行态来自 Agent 上报；目标服务未就绪时，本地端口仍会保留并在连接时返回明确错误。</p>}
    </>
  )
}
