# BMC client

The installable PWA every BMC screen is built on: React 19, Vite 8, TypeScript (strict), Tailwind 4, shadcn/ui,
TanStack Router (file routes) and TanStack Query, zod contracts, vite-plugin-pwa. Built to static files and served by
nginx behind Traefik ([ADR-002](../../documents/adr/ADR-002-technology-stack.md),
[ADR-011](../../documents/adr/ADR-011-installable-pwa-client.md)).

Every port has a **mock** adapter, so the whole app runs today, with no backend and no Keycloak. Switching a port to
the real API is one line (see [Switch mock → HTTP](#switch-mock--http)).

> ADR-009 sketches this app at `apps/client`; it lives here, in `services/client`, where CI builds it
> (`.github/services.json`).

## Commands

Needs Node 20.19+ or 22.12+ (Vite 8). On an older Node, `npm ci` silently skips the bundler's native binary and the
build and tests fail with "Cannot find native binding".

```bash
npm ci               # install (exact versions from package-lock.json)
npm run dev          # http://localhost:5173, mock adapters, ?mockFail=<status> enabled
npm run verify       # typecheck → lint → format check → tests → build (run before every PR)
npm test             # tests only (Vitest); npm run test:watch while working
npm run lint         # ESLint, including the architecture rules
npm run format       # Prettier
npm run build        # tsc -b && vite build → dist/ (what CI and the Docker image run)
npm run preview      # serve dist/ with the same proxy as dev
npm run icons        # regenerate public/ icons from public/favicon.svg
```

CI (`.github/actions/build-test`) runs `npm ci`, `npm run build` and `npm test`, so type errors and tests block a
merge. Lint and the format check run in `npm run verify` only, until they are added to CI.

## The map

```text
src/
  app/          composition root: config.ts (env), container.ts (ports → adapters), router.tsx, query-client.ts,
                context.tsx (ServicesProvider, useServices, useAppContext), loaders.ts (routeData)
  contracts/    zod schemas + inferred types, one file per backend service; problem.ts (RFC 9457 errors)
  ports/        interfaces (and the models they return) the UI depends on: AuthSession, IssueApi, Clock; services.ts bundles them
  adapters/
    http/       client.ts (the one fetch wrapper) + one adapter per port, over /api/<service>/…
    mock/       in-memory adapters, synthetic personas and data, simulated latency and failures
  features/     <feature>/ api.ts (query keys, queryOptions, mutations), components, index.ts (public API)
  routes/       TanStack file routes, thin: guards, loaders, which page to render. `_app` = signed-in layout
  shared/       ui/ (shadcn), components/, hooks/, lib/ (errors, ids, redirect, format), i18n/, theme.tsx
  test/         test setup and renderApp()
test/           checks on static files (index.html has no inline script, nginx serves the CSP)
```

### Dependency rules

Enforced by `npm run lint` (eslint-plugin-boundaries, `eslint.config.js`). If one fails, fix the import; do not add an
exception.

| Layer       | May import                                                                                           |
| ----------- | ---------------------------------------------------------------------------------------------------- |
| `app`       | everything (it is the composition root)                                                              |
| `routes`    | `app/context.tsx`, `app/loaders.ts`, features **through their `index.ts`**, ports, contracts, shared |
| `features`  | the same as routes; another feature only through its `index.ts`                                      |
| `adapters`  | ports, contracts, shared                                                                             |
| `ports`     | contracts, shared                                                                                    |
| `contracts` | nothing local (zod only)                                                                             |
| `shared`    | shared only                                                                                          |

- **Features never import adapters.** They get ports from `useServices()` (components) or the router context
  (loaders, guards). That is what makes mock ↔ HTTP a one-line switch.
- **Imports inside `src/` use `@/` only**: `@/shared/ui/button`, never `./button` or `../ui/button`. Lint enforces it.
  The generated `src/routeTree.gen.ts` is the only exception.
- **The network and the environment have one door each.** Lint rejects `fetch`, `XMLHttpRequest`, `WebSocket` and
  `EventSource` outside `src/adapters/http/`, and `import.meta.env` outside `src/app/config.ts`.

## How the pieces fit

**Dependency injection.** `createContainer(config)` builds every adapter once and returns `Services`, an object of
ports. `createAppRouter()` puts it on the router context (for loaders and guards) and in `<ServicesProvider>` (for
`useServices()` in components). No DI library: ADR-009 rejected InversifyJS because esbuild cannot emit decorator
metadata.

**Contracts.** Each payload is a zod schema in `src/contracts/`; its TypeScript type is `z.infer` of it, never written
twice. The HTTP client parses every 2xx body with its schema, so client/server drift fails loudly as a
`ContractError` instead of rendering `undefined`. Fields marked `ASSUMPTION` are guesses to align with the service
when it exists.

**Errors.** Adapters, HTTP or mock, throw only `ApiError` (status, problem `code`, `correlationId`), `NetworkError`
(no answer) or `ContractError` (`src/shared/lib/errors.ts`). `<ErrorState>` explains any of them in place, with the
reference and a retry. A 404 in a loader becomes the not-found page (`routeData`), and any 401 anywhere ends the
session (`createQueryClient` → `expireSession`).

A report refused with `401` is not lost on the way through sign-in: the form keeps it in `sessionStorage`
(`src/shared/lib/unsent-report.ts`) under the author's `sub` and takes it back, once, when the same person returns
within 15 minutes. It is removed, unread, as soon as anyone else is signed in (threat T-20). Nothing else the user
typed is stored in the browser.

**HTTP client** (`src/adapters/http/client.ts`). Same origin (`credentials: "same-origin"`, no CORS), `cache:
"no-store"`. On every request it sets `X-Correlation-Id`. On every write it sets the CSRF header `X-BMC-CSRF: 1`, a
**placeholder** whose name is open in EN01-U6 (`CSRF_HEADER`, one constant). On every POST it sets an
`Idempotency-Key`, which you can pass in to make a retry of the same action safe. `If-Match` is opt-in via `ifMatch`.

**Authentication** (ADR-005: BFF at the edge). The browser never sees a token. `AuthSession.me()` is
`GET /api/issue/me`, where a 401 means "no session". `login()` and `logout()` navigate to the edge OIDC plugin's paths;
their HTTP promises never settle, because the page is leaving.

The root route resolves the session before any guard runs:

- it reuses the session for 5 minutes, then asks again on the next navigation;
- a network blip keeps the known session;
- an unusable `/me` shows on the login page, with a sign-out button.

`/me` is the Issue service's `ProfileResponse` (`ProfileSchema`, US-01-01). The HTTP auth adapter maps it to the
client's `Session` model (`src/ports/auth-session.ts`), so the UI never depends on wire names. `/me` carries the
email and the platform roles (EN-02-04, #139). It does not carry a contractor link, which belongs to Dispatch (#80), so
over HTTP the contractor area does not appear.

**Mock personas** are the Issue service's synthetic seed users (`R__synthetic_fixtures.sql`), with the same IDs, names
and buildings: Residents A1, A2 and B1, Administrators A and B, and the contractor account. Platform staff is mock only,
because the seed has no such account. Switching a port to HTTP against the seeded local stack shows the same people.

`watchSession` reacts when a background refetch finds the session ended, or a different account: it wipes the previous
person's cached data and re-runs the guards.

Guards are UX, never the security control:

- `requireSession` (`_app` layout) sends you to `/login?redirect=…`;
- `requirePlatformRole` answers **not found**, never "forbidden";
- `hasBuildingRole(session, buildingId, role)` checks roles per building, because roles are never global.

`safeRedirect` blocks open redirects through `?redirect=`. The edge sign-in ignores `?redirect=` and always lands on
`/`, so the HTTP adapter keeps the return path in `sessionStorage` (`rememberReturnTo`) and the `/` route takes it once,
through `safeRedirect`, when `/me` names someone.

**Server state** lives in TanStack Query only. Each feature has a key factory and one `queryOptions` per resource,
shared by its loader and its component. Writes are never retried automatically.

**PWA** (ADR-011). The service worker precaches the shell and static assets and never caches API responses. A new
version waits for the user ("A new version of BMC is available — Reload"). Install is one click on Chromium; Safari
gets guided steps (iOS: Share → Add to Home Screen; macOS: File › Add to Dock). The app is online only: a banner says
so while offline, and `<SubmitButton>` disables itself with the reason next to it. The service worker needs HTTPS or
localhost, so it does not register on the plain-HTTP deployment until a domain and TLS exist.

**Theme.** Black on white and white on black, Inter (self-hosted through `@fontsource-variable`, no Google Fonts).
The theme follows the system with a toggle, and `public/theme-init.js` applies it before the first paint. Semantic
colours appear only with an icon and text (NFR-07).

**Strings.** Every user-facing string is in `src/shared/i18n/en.ts`, so adding pt-PT touches no component.

**Page titles and focus** (WCAG 2.4.2, 2.4.3). `<PageHeader>` sets the tab title to "<page> · BMC" and renders the
page's `<h1>`. After each navigation (sign-in and sign-out included), the root route moves focus to that heading without scrolling, so screen readers announce the new
page. A page without `<PageHeader>` renders `<DocumentTitle>` and an `<h1 tabIndex={-1}>` itself.

## Recipes

### Add a port and its adapters

1. Schema(s) in `src/contracts/<service>.ts`.
2. Interface in `src/ports/<name>.ts`; add it to `Services` in `src/ports/services.ts`.
3. HTTP adapter `src/adapters/http/<name>.ts` (`http.get(path, Schema)`, `http.request("POST", path, { schema, body })`).
4. Mock adapter `src/adapters/mock/<name>.ts`, with every call through `runtime.call(...)` (latency, `?mockFail`).
5. Wire both in `src/app/container.ts`, and add the port name to `SwitchablePort`.
6. Tests: the HTTP adapter with a fake `fetch` (see `client.test.ts`); the mock if it has rules.

### Add a feature and a route

1. `src/features/<feature>/api.ts`: key factory + `queryOptions` / `mutationOptions` taking the port.
2. Page component(s) using `useServices()` + `useQuery(...)`, with loading, error (`<ErrorState>`) and empty states.
3. `src/features/<feature>/index.ts` exports what routes need.
4. Route file `src/routes/_app/<path>.tsx` (signed-in) or `src/routes/<path>.tsx` (public). Prefetch in `loader`
   (page shows its own states), or `await routeData(queryClient.ensureQueryData(...))` (page paints complete, 404 →
   not found). `npm run dev` regenerates `src/routeTree.gen.ts`; commit it.
5. Navigation entry in `src/features/shell/nav.ts` (with `visible` for role-specific items).
6. Strings in `src/shared/i18n/en.ts`.

### Switch mock → HTTP

- Everything: `VITE_ADAPTERS=http` in `.env.local` (dev). The Docker image already builds with `http`; pass
  `--build-arg VITE_ADAPTERS=mock` for an image without a backend.
- One port: uncomment its line in `PORT_ADAPTERS` (`src/app/container.ts`), e.g. `issues: "http"` while sign-in
  stays mocked.

In dev, `/api`, `/oidc`, `/logout` and `/auth` are proxied to `API_PROXY_TARGET` (default `http://localhost:80`, the
local stack's Traefik), so cookies stay first-party.

### See error and degraded states without a backend

In `npm run dev`, add `?mockFail=503` (or 500, 404, 409…) to the address: every mock data call fails with that
status while the parameter is there. Remove it to recover. Sign-in is not affected.

## Environment

Copy `.env.example` to `.env.local`. `VITE_*` values are compiled into the bundle, so they must never hold secrets.

| Variable               | Default               | Meaning                                                            |
| ---------------------- | --------------------- | ------------------------------------------------------------------ |
| `VITE_ADAPTERS`        | `mock`                | `mock` or `http`: the adapters the container wires (image: `http`) |
| `VITE_MOCK_LATENCY_MS` | `300`                 | simulated latency of mock adapters                                 |
| `VITE_LOGIN_URI`       | `/oidc/login`         | edge OIDC plugin login path (placeholder until spike item 3)       |
| `VITE_LOGOUT_URI`      | `/logout`             | edge OIDC plugin logout path                                       |
| `API_PROXY_TARGET`     | `http://localhost:80` | dev/preview server only: where the edge paths are proxied          |

A bad value fails at start-up (`src/app/config.ts`).

## Delivery

`Dockerfile` builds with Node and serves `dist/` with nginx (`nginx.conf`):

- SPA fallback, and a 404 (not `index.html`) for missing hashed assets;
- a 404 (not `index.html`) on the edge's paths (`/api`, `/oidc`, `/logout`, `/auth`). They reach this container only
  while Traefik does not route their backend (starting or down), and a `200` there would read as "ready" or "exposed";
- `immutable` caching for `/assets/*` and `no-cache` for the rest;
- a restrictive Content-Security-Policy with `script-src 'self'`, `X-Frame-Options: DENY`, `nosniff`,
  `Referrer-Policy: no-referrer` and a `Permissions-Policy`.

`npm run preview` sends the same CSP, so a violation shows up locally too. `npm run dev` does not, because Vite's dev
server needs inline scripts. `test/static.test.ts` checks that:

- `index.html` has no inline script;
- the CSP is present;
- nginx and `vite preview` send the same CSP string;
- nginx's 404 block names exactly the paths in `EDGE_PATHS` (`vite.config.ts`).

## Accessibility

`src/app/a11y.test.tsx` renders every shell page and runs axe-core (`vitest-axe`) over it. Every new page adds a case
there. Two rules are off because the test DOM applies no CSS: `color-contrast`, and `landmark-unique`, since the
sidebar and the bottom bar are both "Main" navigation and CSS shows one at a time. axe does not replace the manual
keyboard pass each UI story records in its PR.

## Known limits

- `/me`, the report endpoints and `/buildings/{id}/areas` match the Issue service (US-01-01). The issue, history and
  triage shapes (`GET /issues`, `GET /issues/{id}`, `GET /issues/{id}/history`, `POST /issues/{id}/triage`) match
  the service's pull request #140 (US-02-01), which is not merged yet. History types for later stories stay marked
  `ASSUMPTION` in `src/contracts/issue.ts`. The US-03-02 additions are agreed with the Issue service owner but not
  built yet: the suggestion's `rationale` (`null` in lists), `needsReview`, `failureCode` and `suggestedAt` (US-03-03,
  #108), and the issue's `criticalCheck` (`status`, `rulesVersion`, `signals`; US-14-02, #96). They are optional, so
  the service as it is today still parses. Still `ASSUMPTION`: the `failureCode` values (#93, #95) and the signal
  codes (the `critical-alerts-v1` rule set). "Triage pending — classify manually" is worked out in the client from
  `createdAt` (2 minutes, technical design §5); an issue still waiting on a suggestion or on the critical check is
  asked again every 10 seconds. US-14-03 adds `rulesVersion` and `modelVersion` on the suggestion (agreed on #108),
  `origin` and `applied` on `triage_suggested` history entries (#108), and one `critical_alert_raised` entry per
  signal, with its `code` (#96).
- US-04-02 (shared issue) is built on ASSUMPTION shapes until US-04-01 (#89) defines them: `summary`, `impact`
  (`{count, mine}`) and `subscribed` on the issue; `POST /issues/{id}/publication` (`If-Match`, answers the new
  version and summary); `PUT`/`DELETE /issues/{id}/impact` (answers the count) and `/subscription` (answers
  `{subscribed}`); the `summary_published` history type; and residents of the building seeing shared issues in
  `GET /issues`. All are optional, so the service as it is today still parses. Publishing moves the issue version, so
  an open triage form then offers to reload. An administrator also gets their own choices under the publication card:
  notifications, and "this affects me too" when they are a resident of the building as well.
- `/me` never carries the contractor link, which belongs to Dispatch (#80), so over HTTP `contractor` is `null`.
- The edge ignores a return path on sign-in and always lands on `/`; the client keeps it in `sessionStorage` instead
  (#80). The CSRF header and the edge paths match EN-02-04 (#139) but are untested against the real edge until it
  reaches dev.
- `npm audit` reports moderate advisories in `glob`/`minimatch` under `workbox-build`, a build-time dependency of
  vite-plugin-pwa 2.0.0. The production dependencies report none.
