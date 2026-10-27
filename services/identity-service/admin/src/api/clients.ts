export interface OAuthClient {
  id: string
  client_id: string
  name: string
  principal_type: 'user' | 'service' | 'platform'
  owner_tenant_id: string | null
  allowed_tenant_ids: string[]
  audiences: string[]
  scopes: string[]
  redirect_uris: string[]
  logout_uris: string[]
  public_client: boolean
  first_party: boolean
  status: 'ACTIVE' | 'DISABLED'
  version: number
}
export interface IssuedClient {
  client: OAuthClient
  client_secret: string | null
  replayed: boolean
}
export interface SecurityPolicy {
  version: number
  values: Record<string, number>
  effective: Record<string, number>
}
