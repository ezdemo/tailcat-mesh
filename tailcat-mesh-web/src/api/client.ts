import type {
  ApiErrorPayload,
  CreatedEnrollmentToken,
  Connection,
  Device,
  DeviceVirtualNetwork,
  EnrollmentToken,
  Forward,
  ForwardRequest,
  LoginResponse,
  MeshNetwork,
  MeshNetworkMember,
  NetworkMemberRequest,
  NetworkRequest,
  NetworkUpdateRequest,
  Service,
  ServiceRequest,
  TokenCreateRequest,
} from '../types'

const API_BASE_STORAGE_KEY = 'tailcat-mesh.api-base-url'

export function defaultApiBaseUrl(): string {
  return import.meta.env.VITE_API_BASE_URL?.trim() ?? ''
}

export function loadApiBaseUrl(): string {
  try {
    return window.localStorage.getItem(API_BASE_STORAGE_KEY) ?? defaultApiBaseUrl()
  } catch {
    return defaultApiBaseUrl()
  }
}

export function saveApiBaseUrl(value: string): void {
  const normalized = value.trim().replace(/\/$/, '')
  try {
    if (normalized) {
      window.localStorage.setItem(API_BASE_STORAGE_KEY, normalized)
    } else {
      window.localStorage.removeItem(API_BASE_STORAGE_KEY)
    }
  } catch {
    // Storage is optional; the in-memory value still works for this session.
  }
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(message: string, status: number, code = 'TM-CTRL-500') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export class TailcatMeshApi {
  private readonly baseUrl: string
  private readonly accessToken?: string

  constructor(baseUrl = '', accessToken?: string) {
    this.baseUrl = baseUrl.trim().replace(/\/$/, '')
    this.accessToken = accessToken
  }

  async login(username: string, password: string): Promise<LoginResponse> {
    return this.request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
      includeAuth: false,
    })
  }

  async logout(): Promise<void> {
    await this.request<void>('/api/v1/auth/logout', { method: 'POST' })
  }

  async listDevices(): Promise<Device[]> {
    return this.request<Device[]>('/api/v1/devices')
  }

  async getDevice(id: string): Promise<Device> {
    return this.request<Device>(`/api/v1/devices/${encodeURIComponent(id)}`)
  }

  async listDeviceVirtualNetworks(id: string): Promise<DeviceVirtualNetwork[]> {
    return this.request<DeviceVirtualNetwork[]>(`/api/v1/devices/${encodeURIComponent(id)}/virtual-networks`)
  }

  async approveDevice(id: string): Promise<Device> {
    return this.request<Device>(`/api/v1/devices/${encodeURIComponent(id)}/approve`, { method: 'POST' })
  }

  async disableDevice(id: string): Promise<Device> {
    return this.request<Device>(`/api/v1/devices/${encodeURIComponent(id)}/disable`, { method: 'POST' })
  }

  async listEnrollmentTokens(): Promise<EnrollmentToken[]> {
    return this.request<EnrollmentToken[]>('/api/v1/enrollment-tokens')
  }

  async createEnrollmentToken(request: TokenCreateRequest): Promise<CreatedEnrollmentToken> {
    return this.request<CreatedEnrollmentToken>('/api/v1/enrollment-tokens', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async disableEnrollmentToken(id: string): Promise<void> {
    await this.request<void>(`/api/v1/enrollment-tokens/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }

  async listServices(): Promise<Service[]> {
    return this.request<Service[]>('/api/v1/services')
  }

  async createService(request: ServiceRequest): Promise<Service> {
    return this.request<Service>('/api/v1/services', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async updateService(id: string, request: ServiceRequest): Promise<Service> {
    return this.request<Service>(`/api/v1/services/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  async deleteService(id: string): Promise<void> {
    await this.request<void>(`/api/v1/services/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }

  async listForwards(): Promise<Forward[]> {
    return this.request<Forward[]>('/api/v1/forwards')
  }

  async createForward(request: ForwardRequest): Promise<Forward> {
    return this.request<Forward>('/api/v1/forwards', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async updateForward(id: string, request: ForwardRequest): Promise<Forward> {
    return this.request<Forward>(`/api/v1/forwards/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  async deleteForward(id: string): Promise<void> {
    await this.request<void>(`/api/v1/forwards/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }

  async listConnections(): Promise<Connection[]> {
    return this.request<Connection[]>('/api/v1/connections')
  }

  async listNetworks(): Promise<MeshNetwork[]> {
    return this.request<MeshNetwork[]>('/api/v1/networks')
  }

  async getNetwork(id: string): Promise<MeshNetwork> {
    return this.request<MeshNetwork>(`/api/v1/networks/${encodeURIComponent(id)}`)
  }

  async createNetwork(request: NetworkRequest): Promise<MeshNetwork> {
    return this.request<MeshNetwork>('/api/v1/networks', {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async updateNetwork(id: string, request: NetworkUpdateRequest): Promise<MeshNetwork> {
    return this.request<MeshNetwork>(`/api/v1/networks/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  async deleteNetwork(id: string): Promise<void> {
    await this.request<void>(`/api/v1/networks/${encodeURIComponent(id)}`, { method: 'DELETE' })
  }

  async addNetworkMember(id: string, request: NetworkMemberRequest): Promise<MeshNetworkMember> {
    return this.request<MeshNetworkMember>(`/api/v1/networks/${encodeURIComponent(id)}/members`, {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async removeNetworkMember(networkId: string, deviceId: string): Promise<void> {
    await this.request<void>(
      `/api/v1/networks/${encodeURIComponent(networkId)}/members/${encodeURIComponent(deviceId)}`,
      { method: 'DELETE' },
    )
  }

  private async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const headers = new Headers(options.headers)
    headers.set('Accept', 'application/json')
    if (options.body !== undefined) {
      headers.set('Content-Type', 'application/json')
    }
    if (options.includeAuth !== false && this.accessToken) {
      headers.set('Authorization', `Bearer ${this.accessToken}`)
    }

    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        method: options.method ?? 'GET',
        headers,
        body: options.body,
      })
    } catch {
      throw new ApiError('无法连接控制面，请检查 Server 是否已启动。', 0, 'NETWORK_ERROR')
    }

    if (response.ok) {
      if (response.status === 204) {
        return undefined as T
      }
      return (await response.json()) as T
    }

    let payload: ApiErrorPayload = {}
    try {
      payload = (await response.json()) as ApiErrorPayload
    } catch {
      // Fall back to the HTTP status when the response is not JSON.
    }
    throw new ApiError(
      payload.message ?? `请求失败（HTTP ${response.status}）`,
      response.status,
      payload.code ?? `HTTP_${response.status}`,
    )
  }
}

interface RequestOptions {
  method?: string
  body?: string
  headers?: HeadersInit
  includeAuth?: boolean
}
