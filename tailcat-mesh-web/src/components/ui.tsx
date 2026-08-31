import { useId, type ButtonHTMLAttributes, type ComponentType, type HTMLAttributes, type ReactNode } from 'react'
import { AlertCircle, CheckCircle2, Info, LoaderCircle, X } from 'lucide-react'

export function cn(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(' ')
}

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

export function Button({
  children,
  className,
  variant = 'primary',
  loading = false,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant
  loading?: boolean
}) {
  const variants: Record<ButtonVariant, string> = {
    primary: 'bg-ink text-white shadow-card hover:bg-slate-800 focus-visible:outline-ink',
    secondary: 'bg-paper-raised text-ink ring-1 ring-inset ring-slate-300 hover:bg-slate-50 focus-visible:outline-ink',
    ghost: 'text-slate-700 hover:bg-slate-100 hover:text-ink focus-visible:outline-ink',
    danger: 'bg-rose-700 text-white shadow-card hover:bg-rose-600 focus-visible:outline-rose-700',
  }
  return (
    <button
      className={cn(
        'inline-flex min-h-11 items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-semibold transition duration-200 ease-anthropic focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
        variants[variant],
        className,
      )}
      disabled={disabled || loading}
      {...props}
    >
      {loading && <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" />}
      {children}
    </button>
  )
}

export function Badge({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold ring-1 ring-inset', className)}>
      {children}
    </span>
  )
}

export function Card({ children, className }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('rounded-lg bg-paper-raised shadow-card ring-1 ring-slate-200/80', className)}>{children}</div>
}

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow?: string
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <div className="mb-10 flex flex-col justify-between gap-6 sm:flex-row sm:items-end">
      <div>
        {eyebrow && <p className="mb-3 font-mono text-[11px] font-medium uppercase tracking-[0.18em] text-slate-500">{eyebrow}</p>}
        <h1 className="text-[clamp(2.75rem,6vw,3.625rem)] font-bold leading-[1.1] tracking-[-0.05em] text-ink">{title}</h1>
        {description && <p className="mt-4 max-w-2xl text-sm leading-5 text-slate-500">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-3">{actions}</div>}
    </div>
  )
}

export function Notice({
  tone = 'info',
  title,
  message,
  onClose,
}: {
  tone?: 'info' | 'success' | 'error'
  title?: string
  message: string
  onClose?: () => void
}) {
  const tones = {
    info: 'bg-sky-50 text-sky-800 ring-sky-200',
    success: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
    error: 'bg-rose-50 text-rose-800 ring-rose-200',
  }
  const Icon = tone === 'error' ? AlertCircle : tone === 'success' ? CheckCircle2 : Info
  return (
    <div className={cn('flex items-start gap-3 rounded-lg px-4 py-3 text-sm ring-1', tones[tone])} role={tone === 'error' ? 'alert' : 'status'}>
      <Icon className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        {title && <p className="font-semibold">{title}</p>}
        <p className={title ? 'mt-0.5' : ''}>{message}</p>
      </div>
      {onClose && (
        <button className="flex min-h-11 min-w-11 items-center justify-center rounded p-1 opacity-70 transition hover:opacity-100" onClick={onClose} aria-label="关闭提示">
          <X className="h-4 w-4" aria-hidden="true" />
        </button>
      )}
    </div>
  )
}

export function EmptyState({ icon: Icon, title, description, action }: {
  icon: ComponentType<{ className?: string }>
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-16 text-center">
      <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600">
        <Icon className="h-6 w-6" aria-hidden="true" />
      </div>
      <h3 className="mt-5 text-sm font-semibold text-ink">{title}</h3>
      <p className="mt-2 max-w-sm text-sm leading-5 text-slate-500">{description}</p>
      {action && <div className="mt-6">{action}</div>}
    </div>
  )
}

export function Modal({
  open,
  title,
  description,
  onClose,
  children,
  size = 'md',
}: {
  open: boolean
  title: string
  description?: string
  onClose: () => void
  children: ReactNode
  size?: 'md' | 'lg'
}) {
  const titleId = useId()
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center p-4 sm:items-center" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <button className="fixed inset-0 bg-slate-950/60 backdrop-blur-[2px]" onClick={onClose} aria-label="关闭弹窗" />
      <div className={cn('relative max-h-[90vh] w-full overflow-y-auto rounded-lg bg-paper-raised shadow-elevated ring-1 ring-slate-950/10', size === 'lg' ? 'max-w-2xl' : 'max-w-lg')}>
        <div className="flex items-start justify-between border-b border-slate-100 px-6 py-5">
          <div>
            <h2 id={titleId} className="text-base font-semibold text-ink">{title}</h2>
            {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
          </div>
          <button className="flex min-h-11 min-w-11 items-center justify-center rounded-lg p-2 text-slate-400 transition hover:bg-slate-100 hover:text-ink" onClick={onClose} aria-label="关闭弹窗">
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>
        <div className="px-6 py-5">{children}</div>
      </div>
    </div>
  )
}

export function Spinner({ className }: { className?: string }) {
  return <LoaderCircle className={cn('h-5 w-5 animate-spin text-ink', className)} aria-label="加载中" />
}
