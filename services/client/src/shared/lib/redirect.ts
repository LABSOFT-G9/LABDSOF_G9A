const hasControlOrBackslash = (value: string) =>
  [...value].some((char) => char === "\\" || char.charCodeAt(0) < 0x20 || char.charCodeAt(0) === 0x7f);

/**
 * Same-origin relative paths only, so `?redirect=` can never send the user to another site (open redirect).
 * Control characters and backslashes are refused too: browsers strip tabs and newlines while parsing, so
 * `/\t/evil.test` would become `//evil.test` once it reaches a `Location` header (e.g. via the edge's login redirect).
 */
export function safeRedirect(target: unknown, fallback = "/dashboard"): string {
  if (typeof target !== "string" || !target.startsWith("/") || target.startsWith("//")) return fallback;
  if (hasControlOrBackslash(target)) return fallback;
  return target;
}
