export type DeviceStatus = 'PENDING' | 'ONLINE' | 'OFFLINE' | 'DISABLED'

export interface LoginResponse {
  accessToken: string
  expiresAt: string
}

export interface Device {
  id: string
  networkId: string
  name: string
  hostname: string
  os: string
  arch: string
  status: DeviceStatus
  agentVersion: string
  tailcatVersion: string
  clientPublicKey: string | null
  serverConnBlobHash: string | null
  lastSeenAt: string | null
  desiredRevision: number
  createdAt: string
  updatedAt: string
  virtualNetworks?: DeviceVirtualNetwork[]
}

export interface DeviceVirtualNetwork {
  networkId: string
  networkName: string
  networkSlug: string
  cidr: string
  virtualIpv4: string
  networkEnabled: boolean
  memberEnabled: boolean
}

export interface EnrollmentToken {
  id: string
  networkId: string
  expiresAt: string
  maxUses: number
  usedCount: number
  enabled: boolean
  createdAt: string
}

export interface CreatedEnrollmentToken {
  id: string
  token: string
  expiresAt: string
  maxUses: number
}

export interface ApiErrorPayload {
  code?: string
  message?: string
  timestamp?: string
}

export interface AuthSession {
  accessToken: string
  expiresAt: string
}

export interface TokenCreateRequest {
  maxUses: number
  expiresInHours: number
}

export type ServiceStatus = 'STARTING' | 'READY' | 'FAILED' | 'STOPPED'

export interface Service {
  id: string
  deviceId: string
  name: string
  protocol: 'TCP'
  targetHost: string
  targetPort: number
  enabled: boolean
  bridgePort: number | null
  status: ServiceStatus
  lastError: string | null
  createdAt: string
  updatedAt: string
}

export interface ServiceRequest {
  deviceId: string
  name: string
  protocol: 'TCP'
  targetHost: string
  targetPort: number
  enabled: boolean
}

export type ForwardStatus = 'STARTING' | 'READY' | 'ERROR' | 'STOPPED'

export interface Forward {
  id: string
  sourceDeviceId: string
  sourceDeviceName: string
  remoteServiceId: string
  remoteServiceName: string
  remoteDeviceId: string | null
  remoteDeviceName: string
  name: string
  localBindHost: string
  localBindPort: number
  enabled: boolean
  status: ForwardStatus
  errorCode: string | null
  lastError: string | null
  createdAt: string
  updatedAt: string
}

export interface ForwardRequest {
  sourceDeviceId: string
  remoteServiceId: string
  name: string
  localBindHost: '127.0.0.1' | '::1'
  localBindPort: number
  enabled: boolean
}

export type ConnectionStatus = 'ONLINE' | 'DEGRADED' | 'OFFLINE' | 'UNKNOWN' | 'STOPPED'
export type ConnectionPathType = 'DIRECT' | 'DERP' | 'OFFLINE' | 'UNKNOWN'

export interface Connection {
  sourceDeviceId: string
  sourceDeviceName: string
  peerDeviceId: string
  peerDeviceName: string
  status: ConnectionStatus
  pathType: ConnectionPathType
  latencyMs: number | null
  derpRegion: string | null
  directEndpoint: string | null
  lastCheckAt: string | null
  lastError: string | null
}

export interface MeshNetworkMember {
  id: string
  networkId: string
  deviceId: string
  deviceName: string
  hostname: string
  deviceStatus: DeviceStatus
  virtualIpv4: string
  joinedAt: string
  enabled: boolean
}

export interface MeshNetwork {
  id: string
  name: string
  slug: string
  cidr: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  members: MeshNetworkMember[]
  peerPaths?: NetworkPeerPath[]
}

export type NetworkPeerStatus = 'ONLINE' | 'DEGRADED' | 'OFFLINE' | 'UNKNOWN' | 'STOPPED'
export type NetworkPeerPathType = 'DIRECT' | 'DERP' | 'OFFLINE' | 'UNKNOWN'

export interface NetworkPeerPath {
  sourceDeviceId: string
  sourceDeviceName: string
  peerDeviceId: string
  peerDeviceName: string
  status: NetworkPeerStatus
  pathType: NetworkPeerPathType
  latencyMs: number | null
  derpRegion: string | null
  directEndpoint: string | null
  lastCheckAt: string | null
  lastError: string | null
}

export interface NetworkRequest {
  name: string
  cidr?: string
}

export interface NetworkUpdateRequest {
  name?: string
  cidr?: string
  enabled?: boolean
}

export interface NetworkMemberRequest {
  deviceId: string
  virtualIpv4?: string
}
