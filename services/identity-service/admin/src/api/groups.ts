export interface Group {
  id: string
  parent_id: string | null
  code: string
  name: string
  description: string
  sort: number
  status: 'ACTIVE' | 'DISABLED'
  version: number
  effective_status: 'ACTIVE' | 'DISABLED'
  group_path: string
  organization_version: number
  has_children: boolean
  member_count: number
  subtree_member_count: number
  descendant_count: number
  role_count: number
}

export interface Reference {
  id: string
  version: number
  name?: string
  display_name?: string
  username?: string
  code?: string
  group_path?: string
  status?: string
  effective_status?: string
  protected?: boolean
}

export function references(values: Reference[]) {
  return values.map(({ id, version }) => ({ id, version }))
}
