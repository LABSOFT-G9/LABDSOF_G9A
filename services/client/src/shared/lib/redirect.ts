/** Printable ASCII only, and no backslash. Anything else is either an attack or not a path the router built. */
const isUnsafe = (value: string) =>
  [...value].some((char) => char === "\\" || char.charCodeAt(0) < 0x20 || char.charCodeAt(0) > 0x7e);

/**
 * Same-origin relative paths only, so `?redirect=` can never send the user to another site (open redirect).
 * Control characters, backslashes and non-ASCII are refused too (the router percent-encodes what it builds): browsers strip tabs and newlines while parsing, so
 * `/\t/evil.test` would become `//evil.test` once it reaches a `Location` header (e.g. via the edge's login redirect).
 */
export function safeRedirect(target: unknown, fallback = "/dashboard"): string {
  if (typeof target !== "string" || !target.startsWith("/") || target.startsWith("//")) return fallback;
  if (isUnsafe(target)) return fallback;
  return target;
}
