/** Same-origin relative paths only, so `?redirect=` can never send the user to another site (open redirect). */
export function safeRedirect(target: unknown, fallback = "/dashboard"): string {
  if (typeof target !== "string" || !target.startsWith("/") || target.startsWith("//") || target.startsWith("/\\")) {
    return fallback;
  }
  return target;
}
