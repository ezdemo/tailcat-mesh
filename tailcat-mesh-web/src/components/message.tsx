import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react'
import { cn } from './ui'

export type MessageTone = 'success' | 'error' | 'info'

export interface MessageOptions {
  tone: MessageTone
  message: string
  title?: string
  duration?: number
}

interface MessageRecord extends MessageOptions {
  id: string
}

interface MessageContextValue {
  showMessage: (options: MessageOptions) => string
  showSuccess: (message: string, title?: string) => string
  showError: (message: string, title?: string) => string
  showInfo: (message: string, title?: string) => string
  dismissMessage: (id: string) => void
}

const MessageContext = createContext<MessageContextValue | null>(null)

const toneStyles: Record<MessageTone, { container: string; icon: string; Icon: typeof CheckCircle2 }> = {
  success: {
    container: 'bg-emerald-50 text-emerald-900 ring-emerald-200',
    icon: 'text-emerald-600',
    Icon: CheckCircle2,
  },
  error: {
    container: 'bg-rose-50 text-rose-900 ring-rose-200',
    icon: 'text-rose-600',
    Icon: AlertCircle,
  },
  info: {
    container: 'bg-sky-50 text-sky-900 ring-sky-200',
    icon: 'text-sky-600',
    Icon: Info,
  },
}

export function MessageProvider({ children }: { children: ReactNode }) {
  const [messages, setMessages] = useState<MessageRecord[]>([])
  const nextId = useRef(0)
  const timers = useRef(new Map<string, number>())

  const dismissMessage = useCallback((id: string) => {
    const timer = timers.current.get(id)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timers.current.delete(id)
    }
    setMessages((current) => current.filter((message) => message.id !== id))
  }, [])

  const showMessage = useCallback((options: MessageOptions) => {
    const id = 'message-' + (++nextId.current)
    const record: MessageRecord = { ...options, id }
    setMessages((current) => [...current, record].slice(-5))
    if (options.duration !== 0) {
      const timer = window.setTimeout(() => dismissMessage(id), options.duration ?? 3600)
      timers.current.set(id, timer)
    }
    return id
  }, [dismissMessage])

  const showSuccess = useCallback((message: string, title?: string) => showMessage({ tone: 'success', message, title }), [showMessage])
  const showError = useCallback((message: string, title?: string) => showMessage({ tone: 'error', message, title }), [showMessage])
  const showInfo = useCallback((message: string, title?: string) => showMessage({ tone: 'info', message, title }), [showMessage])

  useEffect(() => {
    return () => {
      timers.current.forEach((timer) => window.clearTimeout(timer))
      timers.current.clear()
    }
  }, [])

  return (
    <MessageContext.Provider value={{ showMessage, showSuccess, showError, showInfo, dismissMessage }}>
      {children}
      <div className="pointer-events-none fixed inset-x-4 top-4 z-[100] flex flex-col items-end gap-3 sm:left-auto sm:right-6 sm:max-w-sm" aria-live="polite">
        {messages.map((message) => {
          const { Icon } = toneStyles[message.tone]
          return (
            <div
              key={message.id}
              className={cn('pointer-events-auto flex w-full items-start gap-3 rounded-xl px-4 py-3 text-sm shadow-elevated ring-1', toneStyles[message.tone].container)}
              role={message.tone === 'error' ? 'alert' : 'status'}
            >
              <Icon className={cn('mt-0.5 h-4 w-4 shrink-0', toneStyles[message.tone].icon)} aria-hidden="true" />
              <div className="min-w-0 flex-1">
                {message.title && <p className="font-semibold">{message.title}</p>}
                <p className={message.title ? 'mt-0.5' : ''}>{message.message}</p>
              </div>
              <button
                type="button"
                className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md opacity-60 transition hover:bg-black/5 hover:opacity-100"
                onClick={() => dismissMessage(message.id)}
                aria-label="关闭消息"
              >
                <X className="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          )
        })}
      </div>
    </MessageContext.Provider>
  )
}

export function useMessage(): MessageContextValue {
  const context = useContext(MessageContext)
  if (!context) {
    throw new Error('useMessage must be used inside MessageProvider')
  }
  return context
}
