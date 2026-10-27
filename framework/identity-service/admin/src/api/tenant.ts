export interface TenantUser {
  id: string
  username: string
  display_name: string
  status: 'ACTIVE' | 'DISABLED' | 'PENDING_ACTIVATION'
  must_change_password: boolean
  version: number
}

export type DataScope = 'SELF' | 'TENANT' | null
export interface Role {
  id: string
  code: string
  name: string
  status: 'ACTIVE' | 'DISABLED'
  protected: boolean
  version: number
  authorization_version: number
  can_assign: boolean
  can_edit: boolean
  can_unassign: boolean
  can_manage?: boolean
  description?: string
  restrictions: string[]
}
export interface RolePage {
  page: number
  size: number
  has_more: boolean
  items: Role[]
  can_create: boolean
}
export interface UserRoles {
  user_id: string
  version: number
  roles: Role[]
}
export function managementRestriction(code: string): string {
  const labels: Record<string, string> = {
    ROLE_NOT_ASSIGNABLE: '停用角色不可新增分配',
    PROTECTED_ROLE: '受保护角色禁止普通编辑',
    MANAGEMENT_NOT_ALLOWED: '超出管理委派或目标范围',
    FORBIDDEN: '当前应用未获准此管理操作',
    MANAGEMENT_GRANT_VERSION_REQUIRED: '缺少有效管理委派',
    MANAGEMENT_GRANT_VERSION_CONFLICT: '管理委派已变化',
    ROLE_VERSION_CONFLICT: '角色版本已变化',
  }
  return labels[code] ?? '当前管理规则限制'
}
export interface Permission {
  code: string
  name: string
  owner_service: string
  group_code: string
  group_name: string
  resource_code: string
  resource_name: string
  action_code: string
  description: string
  sort: number
  status: 'ACTIVE' | 'DEPRECATED' | 'DISABLED'
  oauth_scope: string
  supported_data_scopes: Exclude<DataScope, null>[]
  assignable_data_scopes: Exclude<DataScope, null>[]
  can_assign: boolean
}
export interface Catalog {
  catalog_version: number
  items: Permission[]
}
export interface PermissionGrant {
  permission_code: string
  data_scope: DataScope
}
export interface RolePermissions {
  role: Role
  catalog_version: number
  grants: PermissionGrant[]
}

export function permissionReason(code: string): string {
  const reasons: Record<string, string> = {
    ALLOWED: '允许',
    NO_ROLE_GRANT: '缺少角色授权',
    ROLE_DISABLED: '来源角色已停用',
    PERMISSION_DISABLED: '权限已停用',
    INVALID_DATA_SCOPE: '数据范围无效',
    CLIENT_SCOPE_LIMITED: '客户端未获准此 scope',
    TOKEN_SCOPE_LIMITED: '本次 token 未取得此 scope',
    AUDIENCE_MISMATCH: '目标服务不匹配',
    TENANT_CAPABILITY_DISABLED: '租户未启用此管理能力',
  }
  return reasons[code] ?? '当前策略限制'
}

export interface EffectivePermissions {
  view: 'ROLE_GRANTS'
  as_of: string
  user_id: string
  user_version: number
  catalog_version: number
  permissions: Record<
    string,
    {
      allowed: boolean
      data_scope: 'SELF' | 'TENANT' | null
      reasons: string[]
      sources: {
        role_id: string
        data_scope: 'SELF' | 'TENANT'
        role_active: boolean
        authorization_version: number
      }[]
    }
  >
}
