export type Realm = 'platform' | 'tenant'

export interface Brand {
  project_display_name: string
  login_title: string
  admin_title: string
  logo_url: string
  api_major: number
}

export interface Me {
  view: 'APPLICATION_EFFECTIVE'
  sub: string
  principal_type: 'platform' | 'user'
  tenant_id: string | null
  client_id: string
  profile: { name?: string; preferred_username?: string }
  audiences: Record<
    string,
    { permissions: string[]; scopes: string[]; data_scopes: Record<string, string> }
  >
}

export interface Profile {
  id: string
  username: string
  display_name: string
  version: number
}

export interface OwnSession {
  id: string
  created_at: string
  last_activity_at: string
  expires_at: string
  revoked_at: string | null
  current: boolean
  active: boolean
  applications: string[]
}

export interface Consent {
  client_id: string
  name: string
  status: string
  scopes: string[]
}

const messages: Record<string, string> = {
  MANAGEMENT_TARGET_REQUIRED: '请先选择要管理的租户。',
  ACCOUNT_CONFLICT: '当前租户内账号或工号已存在，请核对后再创建。',
  EMPLOYEE_NO_CONFLICT: '当前租户内工号已存在。',
  GROUP_CODE_CONFLICT: '当前租户内用户组编码已存在。',
  GROUP_CYCLE: '不能将组织移动到自身或自己的下级。',
  GROUP_NOT_EMPTY: '请先移除该组的下级组织、直接成员和角色，再删除。',
  GROUP_UNAVAILABLE: '当前组织或上级已停用，请先核对组织状态。',
  GROUP_VERSION_CONFLICT: '用户组已变化，请重新读取后核对。',
  ORGANIZATION_VERSION_CONFLICT: '组织结构已变化，请刷新后重新操作。',
  GROUP_MEMBER_NOT_DIRECT: '此用户属于下级组织，请到实际所属组移除。',
  CATALOG_VERSION_CONFLICT: '权限目录已变化，请重新读取后核对。',
  PERMISSION_CODE_CONFLICT: '权限编码已存在。',
  PERMISSION_SOURCE_PROTECTED: '此权限由框架或服务维护，不能手工修改。',
  PERMISSION_SOURCE_CONFLICT: '权限编码已被其他来源占用。',

  TENANT_UNAVAILABLE: '当前租户已停用或到期，请到“租户管理”核对状态。',
  PASSWORD_RESET_ALREADY_ISSUED:
    '本次重置凭证已签发，不能再次展示。请核对交付结果；重新发起会使旧凭证失效。',
  INCOMPATIBLE_API: '管理页面与服务版本不兼容，请联系管理员。',
  INVALID_TENANT_CODE: '请输入有效的组织代码。',
  RECENT_AUTHENTICATION_REQUIRED: '为保护账号，请重新登录后再执行此操作。',
  CURRENT_PASSWORD_INVALID: '原密码不正确。',
  PASSWORD_REUSE: '新密码不能与原密码相同。',
  PASSWORD_REJECTED: '新密码未满足项目的长度或安全规则，请更换更长的密码。',
  ACCOUNT_FIELDS_INVALID: '名称不能为空，且不能包含控制字符或尖括号。',
  USER_VERSION_CONFLICT: '资料已被其他操作更新。请重新加载后核对修改。',
  PLATFORM_USER_VERSION_CONFLICT: '管理员资料已变化，请核对当前记录后再保存。',
  TENANT_VERSION_CONFLICT: '租户资料已变化，请核对当前记录后再保存。',
  LAST_PLATFORM_ADMIN: '必须保留至少一个有效平台管理员，不能停用最后一位。',
  LAST_TENANT_ADMIN: '必须保留至少一个有效租户管理员，不能停用最后一位。',
  ACCOUNT_ACTIVATION_REQUIRED: '待激活账号必须先完成首次改密，不能直接启停。',
  MANAGEMENT_TARGET_FORBIDDEN: '该目标超出当前管理范围，不能操作本人或受保护的管理账号。',
  USERNAME_CONFLICT: '当前组织内已有此账号，请先查询已有记录。',
  ROLE_OR_CATALOG_VERSION_CONFLICT: '角色或目录已变化，请保留草稿并重新核对。',
  ROLE_VERSION_CONFLICT: '角色已变化，请保留草稿并重新核对。',
  ROLE_CODE_CONFLICT: '当前组织内已有此角色编码，请先查询现有角色。',
  ROLE_INVALID: '角色字段或源角色授权不符合当前规则，请核对后再提交。',
  ROLE_DESCRIPTION_INVALID: '角色说明需为1024字以内纯文本，除换行外不能包含控制字符或尖括号。',
  ROLE_NOT_EMPTY: '请先移除此角色的用户和用户组引用，再重新核对后删除。',
  ROLE_REFERENCED: '角色仍有历史授权引用，当前不能删除；可保留记录并停用。',
  PERMISSION_CATALOG_VERSION_CONFLICT: '权限目录已变化，请重新核对可授范围。',
  MANAGEMENT_NOT_ALLOWED: '目标或完整角色授权超出当前管理范围。',
  PROTECTED_ROLE: '受保护管理员角色不允许从普通编辑入口修改。',
  TENANT_CODE_CONFLICT: '组织代码已存在，请先查询现有租户。',
  PLATFORM_USERNAME_CONFLICT: '平台账号已存在，请先查询现有管理员。',
  REQUEST_ID_CONFLICT: '本次创建内容与已提交请求不一致，请先核对现有记录。',
}

export class IdentityError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly requestId = '',
    readonly uncertain = false,
  ) {
    super(
      messages[code] ??
        (uncertain
          ? '操作结果尚未确认，请检查当前状态后再决定下一步。'
          : status === 401
            ? '登录已失效，请重新登录。'
            : status === 403
              ? '当前账号无权执行此操作。'
              : status === 409
                ? '数据已变化，请重新加载后核对。'
                : status >= 500 || status === 0
                  ? '服务暂时不可用，请稍后重新检查。'
                  : '请求未完成，请检查填写内容。'),
    )
    this.name = 'IdentityError'
  }
}

export function namespace(realm: Realm): string {
  return `/bff/identity/${realm}`
}

async function request<T>(
  path: string,
  method = 'GET',
  body?: unknown,
  csrf?: { header_name: string; token: string },
  requestId?: string,
): Promise<T> {
  const write = method !== 'GET'
  try {
    const headers = new Headers({ Accept: 'application/json' })
    if (body !== undefined) headers.set('Content-Type', 'application/json')
    if (csrf) headers.set(csrf.header_name, csrf.token)
    if (requestId) headers.set('X-Request-Id', requestId)
    const response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      redirect: 'error',
      signal: AbortSignal.timeout(15_000),
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const raw: unknown = await response.json()
    if (!raw || typeof raw !== 'object') throw new IdentityError(503, 'INVALID_RESPONSE', '', write)
    const envelope = raw as Record<string, unknown>
    const code =
      typeof envelope.code === 'string' && /^[A-Z][A-Z0-9_]{0,127}$/.test(envelope.code)
        ? envelope.code
        : 'INVALID_RESPONSE'
    const id =
      typeof envelope.request_id === 'string' &&
      /^[A-Za-z0-9._:-]{1,128}$/.test(envelope.request_id)
        ? envelope.request_id
        : ''
    if (!response.ok || code !== 'OK')
      throw new IdentityError(response.status, code, id, write && response.status >= 500)
    if (!('data' in envelope)) throw new IdentityError(503, 'INVALID_RESPONSE', id, write)
    return envelope.data as T
  } catch (failure) {
    if (failure instanceof IdentityError) throw failure
    throw new IdentityError(0, 'TRANSPORT_UNAVAILABLE', '', write)
  }
}

export async function brand(): Promise<Brand> {
  const value = await request<Brand>('/api/v1/identity/admin-config')
  if (
    value?.api_major !== 1 ||
    typeof value.project_display_name !== 'string' ||
    typeof value.login_title !== 'string' ||
    typeof value.admin_title !== 'string' ||
    typeof value.logo_url !== 'string'
  ) {
    throw new IdentityError(503, 'INCOMPATIBLE_API')
  }
  return value
}

export function read<T>(realm: Realm, path: string): Promise<T> {
  return request<T>(`${namespace(realm)}/api${path}`)
}

export async function csrf(realm: Realm): Promise<{ header_name: string; token: string }> {
  const value = await request<{ header_name: string; token: string }>(`${namespace(realm)}/csrf`)
  if (
    value.header_name !== 'X-CSRF-TOKEN' ||
    typeof value.token !== 'string' ||
    !value.token ||
    value.token.length > 1024
  ) {
    throw new IdentityError(503, 'INVALID_CSRF_RESPONSE')
  }
  return value
}

export async function write<T>(
  realm: Realm,
  path: string,
  body: unknown = {},
  method: 'POST' | 'PUT' | 'DELETE' = 'POST',
  requestId?: string,
): Promise<T> {
  if (requestId && !/^[A-Za-z0-9._:-]{1,128}$/.test(requestId))
    throw new IdentityError(400, 'INVALID_REQUEST_ID')
  const protection = await csrf(realm)
  return request<T>(`${namespace(realm)}/api${path}`, method, body, protection, requestId)
}

export async function logout(realm: Realm): Promise<void> {
  const protection = await csrf(realm)
  await request(`${namespace(realm)}/logout`, 'POST', undefined, protection)
}

export async function current(): Promise<Me> {
  const value = await read<Me>('platform', '/me')
  if (
    !value ||
    value.view !== 'APPLICATION_EFFECTIVE' ||
    value.client_id !== 'identity-admin-platform' ||
    value.principal_type !== 'platform' ||
    typeof value.sub !== 'string' ||
    !value.profile ||
    value.tenant_id !== null
  ) {
    throw new IdentityError(503, 'INVALID_IDENTITY_RESPONSE')
  }
  return value
}

export async function login(): Promise<void> {
  const protection = await csrf('platform')
  const form = document.createElement('form')
  form.action = `${namespace('platform')}/login`
  form.method = 'POST'
  const input = document.createElement('input')
  input.type = 'hidden'
  input.name = '_csrf'
  input.value = protection.token
  form.append(input)
  document.body.append(form)
  form.submit()
}
