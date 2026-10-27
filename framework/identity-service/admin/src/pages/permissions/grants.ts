import type { DataScope, PermissionGrant } from '../../api/tenant'

export interface GrantChange {
  code: string
  kind: '新增' | '移除' | '范围扩大' | '范围收紧' | '范围变化'
  before: DataScope | undefined
  after: DataScope | undefined
}
export function grantMap(grants: PermissionGrant[]): Record<string, DataScope> {
  return Object.fromEntries(grants.map((grant) => [grant.permission_code, grant.data_scope]))
}
export function grantChanges(
  before: Record<string, DataScope>,
  after: Record<string, DataScope>,
): GrantChange[] {
  return [...new Set([...Object.keys(before), ...Object.keys(after)])].sort().flatMap((code) => {
    const old = before[code],
      next = after[code]
    if (old === next) return []
    const kind =
      old === undefined
        ? '新增'
        : next === undefined
          ? '移除'
          : old === 'SELF' && next === 'TENANT'
            ? '范围扩大'
            : old === 'TENANT' && next === 'SELF'
              ? '范围收紧'
              : '范围变化'
    return [{ code, kind, before: old, after: next }]
  })
}
export function scopeName(scope: DataScope | undefined): string {
  return scope === undefined
    ? '未授权'
    : scope === null
      ? '不适用'
      : scope === 'SELF'
        ? '本人数据'
        : '租户数据'
}
