import type { InjectionKey } from 'vue'
import type { Me, Realm } from '../api/identity'

export interface AccountContext {
  realm: Realm
  me: Me
  changed: () => Promise<void>
  signedOut: () => void
  failed: (failure: unknown) => void
}

export const accountContext: InjectionKey<() => AccountContext | null> = Symbol('identity-account')
