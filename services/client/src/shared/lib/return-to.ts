type ReturnToStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

const STORAGE_KEY = "bmc-return-to";

/** `sessionStorage`, or nothing when the browser blocks it (some private modes throw on access). */
function tabStorage(): ReturnToStorage | null {
  try {
    return sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Remembers where to go after the edge sign-in. The edge ignores `?redirect=` and always lands on `/` (EN-02-04,
 * #80), so the path stays in this tab until the app starts again with a session. Storage blocked: the user lands on
 * the dashboard instead.
 */
export function rememberReturnTo(path: string, storage: ReturnToStorage | null = tabStorage()): void {
  try {
    storage?.setItem(STORAGE_KEY, path);
  } catch {
    // Storage full or blocked: nothing to remember.
  }
}

/** Reads and forgets the remembered path. Untrusted: pass it through `safeRedirect` before navigating. */
export function takeReturnTo(storage: ReturnToStorage | null = tabStorage()): string | null {
  try {
    const path = storage?.getItem(STORAGE_KEY) ?? null;
    storage?.removeItem(STORAGE_KEY);
    return path;
  } catch {
    return null;
  }
}
