import { inject } from 'vue'
import { accountContext, type AccountContext } from './context'
import { can } from './permissions'
import { IdentityError, read as apiRead, write as apiWrite, type Realm } from '../api/identity'

export function canManage(owner: AccountContext | null | undefined, permission: string): boolean {
  if (!owner) return false
  if (!owner.targetTenant || owner.me.principal_type !== 'platform') return false
  const mapped: Record<string, string> = {
    'identity:user:read': 'read',
    'identity:user:manage': 'manage',
    'identity:group:read': 'read',
    'identity:group:manage': 'manage',
    'identity:group:assign': 'manage',
    'identity:role:read': 'read',
    'identity:role:manage': 'manage',
    'identity:role:assign': 'manage',
    'identity:permission:read': 'read',
    'identity:session:read': 'read',
    'identity:session:manage': 'manage',
    'identity:audit:read': 'read',
  }
  return (
    !!mapped[permission] &&
    can(owner.me, `platform:identity:${mapped[permission]}`) &&
    (permission !== 'identity:audit:read' || can(owner.me, 'platform:audit:read'))
  )
}
export function managementPath(owner: AccountContext | null | undefined, path: string): string {
  if (owner?.me.principal_type !== 'platform' || !owner.targetTenant)
    throw new IdentityError(400, 'MANAGEMENT_TARGET_REQUIRED')
  return `/platform/tenants/${encodeURIComponent(owner.targetTenant)}/identity${path}`
}
export function useManagementApi() {
  const context = inject(accountContext)
  function destination(realm: Realm, path: string): [Realm, string] {
    const owner = context?.()
    return realm === 'tenant' ? ['platform', managementPath(owner, path)] : ['platform', path]
  }
  return {
    read<T>(realm: Realm, path: string): Promise<T> {
      return apiRead<T>(...destination(realm, path))
    },
    write<T>(
      realm: Realm,
      path: string,
      ...args: Parameters<typeof apiWrite<T>> extends [Realm, string, ...infer A] ? A : never
    ): Promise<T> {
      return apiWrite<T>(...destination(realm, path), ...args)
    },
  }
}
