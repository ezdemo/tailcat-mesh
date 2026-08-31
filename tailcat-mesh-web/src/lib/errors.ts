import { ApiError } from '../api/client'

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '登录状态已失效，请重新登录。'
    if (error.status === 403) return '当前账号没有执行此操作的权限。'
    return `${error.message}（${error.code}）`
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试。'
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401
}
