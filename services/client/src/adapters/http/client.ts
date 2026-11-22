import type { z } from "zod";
import { ProblemSchema } from "@/contracts/problem";
import { ApiError, ContractError, NetworkError } from "@/shared/lib/errors";
import { randomId } from "@/shared/lib/id";

export type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

/**
 * Header every state-changing request carries against CSRF (ADR-005: SameSite=Strict plus a required custom header).
 * PLACEHOLDER: the header's name and where it is enforced are open (EN01-U6); change it here only.
 */
export const CSRF_HEADER = "X-BMC-CSRF";

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export interface RequestOptions<S extends z.ZodType> {
  /** Schema of a 2xx body. Omit for 204 / bodies the caller ignores. */
  readonly schema?: S;
  readonly body?: unknown;
  /** Aggregate version for optimistic locking: sent as `If-Match` (technical design §4.1). */
  readonly ifMatch?: number | string;
  /**
   * `Idempotency-Key` of an effectful POST. Generated when absent; pass the same key to make a retry of the same
   * user action safe.
   */
  readonly idempotencyKey?: string;
  readonly signal?: AbortSignal;
}

export interface HttpClient {
  request<S extends z.ZodType>(method: string, path: string, options?: RequestOptions<S>): Promise<z.output<S>>;
  get<S extends z.ZodType>(path: string, schema: S, signal?: AbortSignal): Promise<z.output<S>>;
}

/**
 * The one HTTP client of the app. Same origin as the page, so the edge's session cookie is sent and there is no CORS.
 * Every 2xx body is parsed with its zod contract; every failure becomes `ApiError`, `NetworkError` or `ContractError`.
 */
export function createHttpClient(
  baseUrl = "",
  fetcher: Fetcher = (input, init) => globalThis.fetch(input, init),
): HttpClient {
  async function request<S extends z.ZodType>(
    method: string,
    path: string,
    { schema, body, ifMatch, idempotencyKey, signal }: RequestOptions<S> = {},
  ): Promise<z.output<S>> {
    const verb = method.toUpperCase();
    const headers = new Headers({ Accept: "application/json", "X-Correlation-Id": randomId() });
    if (!SAFE_METHODS.has(verb)) headers.set(CSRF_HEADER, "1");
    if (verb === "POST") headers.set("Idempotency-Key", idempotencyKey ?? randomId());
    if (ifMatch !== undefined) headers.set("If-Match", `"${ifMatch}"`);
    const isForm = body instanceof FormData;
    if (body !== undefined && !isForm) headers.set("Content-Type", "application/json");

    let response: Response;
    try {
      response = await fetcher(`${baseUrl}${path}`, {
        method: verb,
        headers,
        body: body === undefined ? null : isForm ? body : JSON.stringify(body),
        credentials: "same-origin",
        cache: "no-store",
        signal: signal ?? null,
      });
    } catch (cause) {
      if (signal?.aborted) throw cause;
      throw new NetworkError({ cause });
    }

    const payload = await readBody(response);
    if (!response.ok) throw toApiError(response, payload);
    if (!schema) return undefined as z.output<S>;
    const parsed = schema.safeParse(payload);
    if (!parsed.success) {
      throw new ContractError(
        `${verb} ${path}`,
        parsed.error.issues.map((issue) => `${issue.path.join(".") || "(root)"}: ${issue.message}`),
      );
    }
    return parsed.data;
  }

  return {
    request,
    get: (path, schema, signal) => request("GET", path, { schema, ...(signal ? { signal } : {}) }),
  };
}

async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined;
  const text = await response.text().catch(() => "");
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** A problem body becomes a rich ApiError; anything else (a proxy's HTML page, say) a generic one. */
function toApiError(response: Response, payload: unknown): ApiError {
  const problem = ProblemSchema.safeParse(payload);
  const correlationId = response.headers.get("X-Correlation-Id") ?? undefined;
  if (!problem.success) {
    return new ApiError(
      response.status,
      "UNKNOWN_ERROR",
      `The request failed (HTTP ${response.status}).`,
      correlationId,
    );
  }
  const { code, title, detail, correlationId: bodyCorrelationId, ...rest } = problem.data;
  return new ApiError(
    response.status,
    code ?? "UNKNOWN_ERROR",
    detail ?? title ?? `The request failed (HTTP ${response.status}).`,
    bodyCorrelationId ?? correlationId,
    rest,
  );
}
