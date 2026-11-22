import { z } from "zod";

// The CSP forbids eval (script-src 'self'), so zod must not try its `new Function` fast path.
z.config({ jitless: true });

const AdapterModeSchema = z.enum(["mock", "http"]);
export type AdapterMode = z.infer<typeof AdapterModeSchema>;

const sameOriginPath = z.string().regex(/^\/(?!\/)/, "must be a same-origin path starting with a single /");

const EnvSchema = z.object({
  VITE_ADAPTERS: AdapterModeSchema.default("mock"),
  VITE_MOCK_LATENCY_MS: z.coerce.number().int().min(0).max(10_000).default(300),
  VITE_LOGIN_URI: sameOriginPath.default("/oidc/login"),
  VITE_LOGOUT_URI: sameOriginPath.default("/logout"),
});

export interface AppConfig {
  /** Which adapters the composition root wires by default. */
  readonly adapters: AdapterMode;
  readonly mockLatencyMs: number;
  readonly loginUri: string;
  readonly logoutUri: string;
  /** A development build (`npm run dev`): enables ?mockFail. */
  readonly dev: boolean;
}

/** The only reader of `import.meta.env`. A bad value fails at start-up, loudly, instead of misbehaving later. */
export function readConfig(env: Record<string, unknown> = import.meta.env): AppConfig {
  // An empty variable means "use the default", as in a .env file with `KEY=`.
  const present = Object.fromEntries(Object.entries(env).filter(([, value]) => value !== "" && value !== undefined));
  const parsed = EnvSchema.safeParse(present);
  if (!parsed.success) throw new Error(`Invalid client configuration: ${z.prettifyError(parsed.error)}`);
  const e = parsed.data;
  return {
    adapters: e.VITE_ADAPTERS,
    mockLatencyMs: e.VITE_MOCK_LATENCY_MS,
    loginUri: e.VITE_LOGIN_URI,
    logoutUri: e.VITE_LOGOUT_URI,
    dev: env.DEV === true,
  };
}
