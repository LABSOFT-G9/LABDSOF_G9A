import { PERSONAS } from "@/adapters/mock/data";
import type { MockRuntime } from "@/adapters/mock/runtime";
import type { Me } from "@/contracts/issue";
import type { AuthSession, DevPersonas } from "@/ports/auth-session";

const STORAGE_KEY = "bmc-mock-persona";

export interface MockIdentity {
  current(): Me | null;
}

/** Remembers the chosen persona in localStorage (no token, no secret: a persona id), so a reload keeps it. */
export function createMockIdentity(storage: Pick<Storage, "getItem" | "setItem" | "removeItem"> = localStorage) {
  const read = () => {
    try {
      return storage.getItem(STORAGE_KEY);
    } catch {
      return null;
    }
  };
  return {
    current: (): Me | null => PERSONAS.find((p) => p.id === read())?.session ?? null,
    set(personaId: string | null) {
      try {
        if (personaId) storage.setItem(STORAGE_KEY, personaId);
        else storage.removeItem(STORAGE_KEY);
      } catch {
        // Storage blocked (private mode): the persona lasts until the next reload.
      }
    },
  };
}

export type MockIdentityStore = ReturnType<typeof createMockIdentity>;

/** Sign-in without Keycloak: the session is whichever synthetic persona was chosen on the login page. */
export function mockAuthSession(identity: MockIdentityStore, runtime: MockRuntime): AuthSession & DevPersonas {
  return {
    // Not subject to ?mockFail: a failing session check would hide the whole app instead of showing a page's error.
    me: async () => {
      await runtime.wait();
      return identity.current();
    },
    async login() {
      identity.set(PERSONAS[0]!.id);
    },
    async logout() {
      identity.set(null);
    },
    list: () => PERSONAS.map(({ id, label, description }) => ({ id, label, description })),
    async signInAs(personaId) {
      if (!PERSONAS.some((p) => p.id === personaId)) throw new Error(`Unknown persona: ${personaId}`);
      identity.set(personaId);
    },
  };
}
