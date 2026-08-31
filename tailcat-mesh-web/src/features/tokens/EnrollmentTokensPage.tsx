import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Copy, KeyRound, Plus, RefreshCw, Trash2 } from 'lucide-react'
import type { TailcatMeshApi } from '../../api/client'
import { errorMessage, isUnauthorized } from '../../lib/errors'
import { formatDate, isTokenActive } from '../../lib/format'
import type { CreatedEnrollmentToken, EnrollmentToken } from '../../types'
import { Badge, Button, Card, EmptyState, LoadingState, Modal, Notice, PageHeader } from '../../components/ui'

export function EnrollmentTokensPage({ api, onUnauthorized }: { api: TailcatMeshApi; onUnauthorized: () => void }) {
  const [tokens, setTokens] = useState<EnrollmentToken[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [creating, setCreating] = useState(false)
  const [disablingId, setDisablingId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createdToken, setCreatedToken] = useState<CreatedEnrollmentToken | null>(null)
  const [confirmDisable, setConfirmDisable] = useState<EnrollmentToken | null>(null)
  const [maxUses, setMaxUses] = useState('1')
  const [expiresInHours, setExpiresInHours] = useState('24')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function load(showRefresh = false) {
    setError(null)
    showRefresh ? setRefreshing(true) : setLoading(true)
    try {
      setTokens(await api.listEnrollmentTokens())
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

  const activeCount = useMemo(() => tokens.filter((token) => isTokenActive(token.expiresAt, token.enabled)).length, [tokens])

  async function createToken(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setCreating(true)
    setError(null)
    try {
      const created = await api.createEnrollmentToken({
        maxUses: Number(maxUses),
        expiresInHours: Number(expiresInHours),
      })
      setTokens((current) => [{
        id: created.id,
        networkId: 'default',
        expiresAt: created.expiresAt,
        maxUses: created.maxUses,
        usedCount: 0,
        enabled: true,
        createdAt: new Date().toISOString(),
      }, ...current])
      setCreateOpen(false)
      setCreatedToken(created)
      setMaxUses('1')
      setExpiresInHours('24')
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setCreating(false)
    }
  }

  async function disableToken() {
    if (!confirmDisable) return
    setDisablingId(confirmDisable.id)
    setError(null)
    try {
      await api.disableEnrollmentToken(confirmDisable.id)
      setTokens((current) => current.map((token) => token.id === confirmDisable.id ? { ...token, enabled: false } : token))
      setNotice('加入凭证已禁用。')
      setConfirmDisable(null)
    } catch (reason) {
      if (isUnauthorized(reason)) onUnauthorized()
      setError(errorMessage(reason))
    } finally {
      setDisablingId(null)
    }
  }

  async function copyToken() {
    if (!createdToken) return
    try {
      await navigator.clipboard.writeText(createdToken.token)
      setNotice('加入凭证已复制。')
    } catch {
      setError('无法复制加入凭证，请手动选择文本。')
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="Enrollment"
        title="加入凭证"
        description="创建一次性凭证，让新的 Java Agent 注册到这个控制面。原始凭证只会在创建后显示一次。"
        actions={<><Button variant="secondary" loading={refreshing} onClick={() => void load(true)}><RefreshCw className="h-4 w-4" aria-hidden="true" />刷新</Button><Button onClick={() => setCreateOpen(true)}><Plus className="h-4 w-4" aria-hidden="true" />创建凭证</Button></>}
      />

      <div className="space-y-4">
        {error && <Notice tone="error" title="操作失败" message={error} onClose={() => setError(null)} />}
        {notice && <Notice tone="success" message={notice} onClose={() => setNotice(null)} />}
      </div>

      <div className="mt-6 flex items-center gap-3 rounded-2xl bg-indigo-50 px-5 py-4 text-sm text-indigo-800 ring-1 ring-indigo-100">
        <KeyRound className="h-5 w-5 shrink-0 text-indigo-600" aria-hidden="true" />
        <p><span className="font-semibold">{activeCount}</span> 个有效凭证。凭证只用于首次注册，Agent 注册后会保存自己的控制面 credential。</p>
      </div>

      <Card className="mt-6 overflow-hidden">
        {loading ? (
          <LoadingState />
        ) : tokens.length === 0 ? (
          <EmptyState icon={KeyRound} title="还没有加入凭证" description="创建一个凭证，然后把它交给需要加入 Mesh 的设备管理员。" action={<Button onClick={() => setCreateOpen(true)}><Plus className="h-4 w-4" aria-hidden="true" />创建第一个凭证</Button>} />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full table-fixed divide-y divide-slate-100">
              <thead className="bg-slate-50/70">
                <tr>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 sm:px-6">凭证</th>
                  <th className="px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400">状态</th>
                  <th className="hidden px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 sm:table-cell">使用次数</th>
                  <th className="hidden px-5 py-3 text-left text-[11px] font-bold uppercase tracking-wider text-slate-400 md:table-cell">过期时间</th>
                  <th className="relative px-5 py-3 sm:px-6"><span className="sr-only">操作</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {tokens.map((token) => {
                  const active = isTokenActive(token.expiresAt, token.enabled)
                  return (
                    <tr key={token.id} className="transition hover:bg-slate-50/70">
                      <td className="whitespace-nowrap px-5 py-4 sm:px-6"><div className="flex items-center gap-3"><div className="flex h-9 w-9 items-center justify-center rounded-lg bg-violet-50 text-violet-600"><KeyRound className="h-4 w-4" aria-hidden="true" /></div><div><p className="font-mono text-xs font-medium text-slate-700">{token.id}</p><p className="mt-1 text-xs text-slate-400">创建于 {formatDate(token.createdAt)}</p></div></div></td>
                      <td className="whitespace-nowrap px-5 py-4"><Badge className={active ? 'bg-emerald-50 text-emerald-700 ring-emerald-600/20' : 'bg-slate-100 text-slate-500 ring-slate-500/20'}>{active ? '有效' : token.enabled ? '已过期' : '已禁用'}</Badge></td>
                      <td className="hidden whitespace-nowrap px-5 py-4 text-sm text-slate-600 sm:table-cell"><span className="font-semibold text-slate-900">{token.usedCount}</span> / {token.maxUses}<div className="mt-1 h-1.5 w-24 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-indigo-500" style={{ width: `${Math.min(100, (token.usedCount / token.maxUses) * 100)}%` }} /></div></td>
                      <td className="hidden whitespace-nowrap px-5 py-4 text-xs text-slate-600 md:table-cell">{formatDate(token.expiresAt)}</td>
                      <td className="whitespace-nowrap px-5 py-4 text-right sm:px-6"><Button variant="ghost" className="px-2 text-rose-600 hover:bg-rose-50 hover:text-rose-700" disabled={!token.enabled || disablingId === token.id} onClick={() => setConfirmDisable(token)}><Trash2 className="h-4 w-4" aria-hidden="true" /><span className="hidden sm:inline">禁用</span></Button></td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="创建加入凭证" description="凭证用于 Agent 的首次注册。">
        <form className="space-y-5" onSubmit={createToken}>
          <label className="block"><span className="text-sm font-semibold text-slate-700">最大使用次数</span><input required min="1" max="100000" type="number" value={maxUses} onChange={(event) => setMaxUses(event.target.value)} className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600" /><span className="mt-2 block text-xs text-slate-400">通常给单台设备使用时保持为 1。</span></label>
          <label className="block"><span className="text-sm font-semibold text-slate-700">有效期（小时）</span><input required min="1" max="8760" type="number" value={expiresInHours} onChange={(event) => setExpiresInHours(event.target.value)} className="mt-2 block w-full rounded-xl border-0 bg-slate-50 px-4 py-3 text-sm ring-1 ring-inset ring-slate-200 focus:bg-white focus:ring-2 focus:ring-indigo-600" /></label>
          <div className="flex justify-end gap-3"><Button type="button" variant="secondary" onClick={() => setCreateOpen(false)}>取消</Button><Button type="submit" loading={creating}><Plus className="h-4 w-4" aria-hidden="true" />创建</Button></div>
        </form>
      </Modal>

      <Modal open={createdToken !== null} onClose={() => setCreatedToken(null)} title="加入凭证已创建" description="请立即复制并交给设备管理员；关闭后将无法再次查看原始凭证。">
        {createdToken && <div className="space-y-5"><div className="rounded-xl bg-amber-50 p-4 text-sm leading-6 text-amber-800 ring-1 ring-amber-200">这是唯一一次显示完整凭证。不要把它提交到代码仓库或公开日志。</div><div className="rounded-xl bg-slate-950 p-4"><p className="break-all font-mono text-sm leading-6 text-indigo-200">{createdToken.token}</p></div><div className="flex justify-end gap-3"><Button variant="secondary" onClick={() => setCreatedToken(null)}>关闭</Button><Button onClick={() => void copyToken()}><Copy className="h-4 w-4" aria-hidden="true" />复制凭证</Button></div></div>}
      </Modal>

      <Modal open={confirmDisable !== null} onClose={() => setConfirmDisable(null)} title="禁用加入凭证？" description="之后不能再用它注册新设备。">
        <div className="space-y-5"><div className="rounded-xl bg-rose-50 p-4 text-sm leading-6 text-rose-800 ring-1 ring-rose-200">已完成注册的设备不受影响；只有尚未使用的加入凭证会被阻止。</div><div className="flex justify-end gap-3"><Button variant="secondary" onClick={() => setConfirmDisable(null)}>取消</Button><Button variant="danger" loading={disablingId === confirmDisable?.id} onClick={() => void disableToken()}><Check className="h-4 w-4" aria-hidden="true" />确认禁用</Button></div></div>
      </Modal>
    </>
  )
}
