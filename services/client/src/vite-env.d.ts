/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ADAPTERS?: string;
  readonly VITE_MOCK_LATENCY_MS?: string;
  readonly VITE_LOGIN_URI?: string;
  readonly VITE_LOGOUT_URI?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
