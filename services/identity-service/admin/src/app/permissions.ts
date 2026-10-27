import type { Me } from '../api/identity'

// 仅用于固定界面显示；每次 API 调用仍由服务器独立鉴权。
export function can(me: Me, permission: string): boolean {
  const values = me.audiences?.['identity-service']?.permissions
  return Array.isArray(values) && values.includes(permission)
}
