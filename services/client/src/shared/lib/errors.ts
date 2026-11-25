/**
 * The error vocabulary every port speaks. Adapters (HTTP or mock) throw only these, so features, the query client and
 * the error boundaries handle failures the same way whichever adapter is wired.
 */

/** The server answered with a non-2xx status. Built from an RFC 9457 problem body when there is one. */
export class ApiError extends Error {
  override readonly name = "ApiError";
  constructor(
    readonly status: number,
    /** Problem `code` extension (e.g. `VERSION_CONFLICT`); `UNKNOWN_ERROR` when the body was not a problem. */
    readonly code: string,
    message: string,
    readonly correlationId?: string,
    readonly extensions: Readonly<Record<string, unknown>> = {},
  ) {
    super(message);
  }
}

/** The request never got an answer: offline, DNS, TLS, a dropped connection. Not a sign-out, not a 5xx. */
export class NetworkError extends Error {
  override readonly name = "NetworkError";
  constructor(options?: ErrorOptions) {
    super("The server could not be reached.", options);
  }
}

/** The server answered 2xx with a body that does not match our contract: client and server have drifted apart. */
export class ContractError extends Error {
  override readonly name = "ContractError";
  constructor(
    readonly path: string,
    readonly issues: readonly string[],
  ) {
    super(`The response of ${path} does not match the client's contract.`);
  }
}

export const isApiError = (error: unknown, status?: number): error is ApiError =>
  error instanceof ApiError && (status === undefined || error.status === status);

/** A 4xx other than a throttle is a definite answer; asking again won't change it. */
export const isClientError = (error: unknown): boolean =>
  error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 429;

/** The server could not be asked, could not answer, or asked us to slow down. Never evidence the session ended. */
export const isUnavailable = (error: unknown): boolean =>
  error instanceof NetworkError || (error instanceof ApiError && (error.status >= 500 || error.status === 429));
