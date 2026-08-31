import type { DeviceStatus } from '../types'

export const statusLabels: Record<DeviceStatus, string> = {
  PENDING: '待审批',
  ONLINE: '在线',
  OFFLINE: '离线',
  DISABLED: '已禁用',
}

export const statusStyles: Record<DeviceStatus, string> = {
  PENDING: 'bg-amber-50 text-amber-700 ring-amber-600/20',
  ONLINE: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  OFFLINE: 'bg-slate-100 text-slate-600 ring-slate-500/20',
  DISABLED: 'bg-rose-50 text-rose-700 ring-rose-600/20',
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function formatRelativeDate(value: string | null | undefined): string {
  if (!value) return '从未上报'
  const timestamp = new Date(value).getTime()
  if (Number.isNaN(timestamp)) return '—'
  const seconds = Math.round((Date.now() - timestamp) / 1000)
  if (seconds < 10) return '刚刚'
  if (seconds < 60) return `${seconds} 秒前`
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${Math.round(hours / 24)} 天前`
}

export function shorten(value: string | null | undefined, start = 10, end = 6): string {
  if (!value) return '—'
  if (value.length <= start + end + 3) return value
  return `${value.slice(0, start)}...${value.slice(-end)}`
}

export function isTokenActive(expiresAt: string, enabled: boolean): boolean {
  return enabled && new Date(expiresAt).getTime() > Date.now()
}
