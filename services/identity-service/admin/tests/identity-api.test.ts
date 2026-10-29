import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { brand, csrf, current, IdentityError, login, read, write } from '../src/api/identity'

const fetcher = vi.fn<typeof fetch>()
const envelope = (data: unknown, status = 200, code = 'OK') =>
  new Response(JSON.stringify({ code, data, request_id: 'test-request-1' }), { status })

beforeEach(() => {
  fetcher.mockReset()
  vi.stubGlobal('fetch', fetcher)
  document.body.innerHTML = ''
  sessionStorage.clear()
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('浏览器身份 API 边界', () => {
  it('DELETE 同样逐次取得 CSRF，携带当前版本且不发送浏览器 Bearer', async () => {
    fetcher
      .mockResolvedValueOnce(envelope({ header_name: 'X-CSRF-TOKEN', token: 'delete-csrf' }))
      .mockResolvedValueOnce(envelope({ id: 'role-one', deleted: true }))
    await write('tenant', '/roles/role-one', { version: 4 }, 'DELETE')
    expect(fetcher.mock.calls[0][0]).toBe('/bff/identity/tenant/csrf')
    const [path, options] = fetcher.mock.calls[1]
    expect(path).toBe('/bff/identity/tenant/api/roles/role-one')
    expect(options?.method).toBe('DELETE')
    expect(options?.body).toBe('{"version":4}')
    expect(options?.credentials).toBe('same-origin')
    const headers = new Headers(options?.headers)
    expect(headers.get('X-CSRF-TOKEN')).toBe('delete-csrf')
    expect(headers.has('Authorization')).toBe(false)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('显式创建请求编号随同 CSRF 转发，浏览器仍不发送 token', async () => {
    fetcher
      .mockResolvedValueOnce(envelope({ header_name: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockResolvedValueOnce(envelope({ id: 'created' }))
    await write(
      'platform',
      '/platform/admins',
      { username: 'new-admin', display_name: '管理员' },
      'POST',
      'same-create-request',
    )
    const headers = new Headers(fetcher.mock.calls[1][1]?.headers)
    expect(headers.get('X-Request-Id')).toBe('same-create-request')
    expect(headers.get('X-CSRF-TOKEN')).toBe('csrf')
    expect(headers.has('Authorization')).toBe(false)
  })
  it('读取固定命名空间，只使用同源 Cookie，不发送浏览器 token', async () => {
    fetcher.mockResolvedValue(
      envelope({
        view: 'APPLICATION_EFFECTIVE',
        sub: 'user',
        principal_type: 'platform',
        tenant_id: null,
        client_id: 'identity-admin-platform',
        profile: {},
        audiences: {},
      }),
    )
    await current()
    expect(fetcher).toHaveBeenCalledOnce()
    const [path, options] = fetcher.mock.calls[0]
    expect(path).toBe('/bff/identity/platform/api/me')
    expect(options?.credentials).toBe('same-origin')
    expect(options?.redirect).toBe('error')
    expect(new Headers(options?.headers).has('Authorization')).toBe(false)
    expect(sessionStorage.length).toBe(0)
  })

  it('不接受来自另一客户端或主体类型的当前身份', async () => {
    fetcher.mockResolvedValue(
      envelope({
        view: 'APPLICATION_EFFECTIVE',
        sub: 'user',
        principal_type: 'user',
        tenant_id: 'tenant',
        client_id: 'web-bff',
        profile: {},
      }),
    )
    await expect(current()).rejects.toMatchObject({ code: 'INVALID_IDENTITY_RESPONSE' })
  })

  it('写入逐次取得本命名空间 CSRF，不混用平台和租户状态', async () => {
    fetcher
      .mockResolvedValueOnce(envelope({ header_name: 'X-CSRF-TOKEN', token: 'tenant-csrf' }))
      .mockResolvedValueOnce(envelope({ changed: true }))
      .mockResolvedValueOnce(envelope({ header_name: 'X-CSRF-TOKEN', token: 'platform-csrf' }))
      .mockResolvedValueOnce(envelope({ changed: true }))
    await write('tenant', '/me/profile', { version: 0, display_name: '新姓名' }, 'PUT')
    await write('platform', '/me/password', {
      current_password: 'example-current',
      new_password: 'example-new',
    })
    expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
      '/bff/identity/tenant/csrf',
      '/bff/identity/tenant/api/me/profile',
      '/bff/identity/platform/csrf',
      '/bff/identity/platform/api/me/password',
    ])
    expect(new Headers(fetcher.mock.calls[1][1]?.headers).get('X-CSRF-TOKEN')).toBe('tenant-csrf')
    expect(new Headers(fetcher.mock.calls[3][1]?.headers).get('X-CSRF-TOKEN')).toBe('platform-csrf')
    expect(
      fetcher.mock.calls.every((call) => !new Headers(call[1]?.headers).has('Authorization')),
    ).toBe(true)
  })

  it('权限错误只显示稳定消息，不回显响应正文或自动重试', async () => {
    fetcher.mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 'FORBIDDEN',
          message: 'DO_NOT_ECHO_PRIVATE_BODY',
          data: null,
          request_id: 'test-request-2',
        }),
        { status: 403 },
      ),
    )
    const failure = await read('tenant', '/me/sessions').catch(
      (problem) => problem as IdentityError,
    )
    expect(failure).toBeInstanceOf(IdentityError)
    expect((failure as IdentityError).message).toBe('当前账号无权执行此操作。')
    expect(JSON.stringify(failure)).not.toContain('DO_NOT_ECHO_PRIVATE_BODY')
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('写入传输结果未知时不重放，并明确提示核对状态', async () => {
    fetcher
      .mockResolvedValueOnce(envelope({ header_name: 'X-CSRF-TOKEN', token: 'csrf' }))
      .mockRejectedValueOnce(new TypeError('DO_NOT_ECHO_TRANSPORT'))
    await expect(write('tenant', '/me/profile', { version: 0 })).rejects.toMatchObject({
      uncertain: true,
      status: 0,
    })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('CSRF 获取失败或错误头不能触发写入', async () => {
    fetcher.mockResolvedValueOnce(
      envelope({ header_name: 'Authorization', token: 'server-supplied' }),
    )
    await expect(write('tenant', '/me/password', {})).rejects.toMatchObject({
      code: 'INVALID_CSRF_RESPONSE',
    })
    expect(fetcher).toHaveBeenCalledOnce()
  })

  it('拒绝不兼容品牌 API 和损坏响应', async () => {
    fetcher.mockResolvedValueOnce(envelope({ api_major: 2 }))
    await expect(brand()).rejects.toMatchObject({ code: 'INCOMPATIBLE_API' })
    fetcher.mockResolvedValueOnce(new Response('invalid-json', { status: 200 }))
    await expect(csrf('platform')).rejects.toBeInstanceOf(IdentityError)
  })

  it('管理登录固定使用平台命名空间，只提交 CSRF', async () => {
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {})
    fetcher.mockResolvedValue(envelope({ header_name: 'X-CSRF-TOKEN', token: 'masked-csrf' }))
    await login()
    const form = document.forms[0]
    expect(form.getAttribute('action')).toBe('/bff/identity/platform/login')
    expect(form.method).toBe('post')
    expect(Object.fromEntries(new FormData(form))).toEqual({
      _csrf: 'masked-csrf',
    })
    expect(submit).toHaveBeenCalledOnce()
    expect(sessionStorage.length).toBe(0)
  })

  it('租户管理员会话不能作为后台当前身份', async () => {
    fetcher.mockResolvedValue(
      envelope({
        view: 'APPLICATION_EFFECTIVE',
        sub: 'tenant-admin',
        principal_type: 'user',
        tenant_id: 'alpha',
        client_id: 'identity-admin-tenant',
        profile: {},
        audiences: {},
      }),
    )
    await expect(current()).rejects.toMatchObject({ code: 'INVALID_IDENTITY_RESPONSE' })
    expect(fetcher).toHaveBeenCalledOnce()
  })
})
