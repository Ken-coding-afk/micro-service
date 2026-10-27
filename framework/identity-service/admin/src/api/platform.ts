export interface Page<T> {
  page: number
  size: number
  has_more: boolean
  items: T[]
}
export interface PlatformTenant {
  id: string
  code: string
  name: string
  status: 'ACTIVE' | 'DISABLED'
  expires_at: string | null
  version: number
}
export interface PlatformAdministrator {
  id: string
  username: string
  display_name: string
  status: 'ACTIVE' | 'DISABLED'
  must_change_password: boolean
  version: number
}
export interface IssuedAccount {
  id: string
  initial_password: string | null
  replayed: boolean
}
export interface IssuedTenant {
  tenant_id: string
  admin_id: string
  initial_password: string | null
  replayed: boolean
}
