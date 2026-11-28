import { z } from "zod";

type DraftStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

const STORAGE_KEY = "bmc-unsent-report";

/** Long enough to sign in again, short enough that the text does not wait in an unattended tab. */
export const UNSENT_REPORT_KEPT_MS = 15 * 60_000;

const UnsentReportSchema = z.object({
  sub: z.string(),
  keptAt: z.number(),
  buildingId: z.string(),
  areaId: z.string(),
  text: z.string(),
});
export type UnsentReport = Omit<z.infer<typeof UnsentReportSchema>, "sub" | "keptAt">;

/** `sessionStorage`, or nothing when the browser blocks it (some private modes throw on access). */
function tabStorage(): DraftStorage | null {
  try {
    return sessionStorage;
  } catch {
    return null;
  }
}

function read(storage: DraftStorage | null) {
  try {
    const parsed = UnsentReportSchema.safeParse(JSON.parse(storage?.getItem(STORAGE_KEY) ?? "null"));
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

function remove(storage: DraftStorage | null) {
  try {
    storage?.removeItem(STORAGE_KEY);
  } catch {
    // Storage blocked: there was nothing to remove.
  }
}

/** Kept too long ago, or (the clock moved back) in the future. */
function isExpired(kept: { keptAt: number }, now: number) {
  return now - kept.keptAt > UNSENT_REPORT_KEPT_MS || now < kept.keptAt;
}

/**
 * Keeps a report the service refused with `401` (the session ended while it was being written), so the text is not
 * lost on the way through sign-in. It stays in this tab only, under the author's `sub`, for at most 15 minutes. It is
 * removed when the form has taken it back, on sign-out, when anyone else signs in, and when it expires: at the next
 * start of the app (`forgetStaleUnsentReport`), or by a timer while this page stays open. Storage blocked: the text
 * is lost, as before.
 */
export function keepUnsentReport(
  sub: string,
  report: UnsentReport,
  storage: DraftStorage | null = tabStorage(),
  now: number = Date.now(),
): void {
  try {
    const kept: z.infer<typeof UnsentReportSchema> = { sub, keptAt: now, ...report };
    storage?.setItem(STORAGE_KEY, JSON.stringify(kept));
  } catch {
    // Storage full or blocked: nothing to keep.
    return;
  }
  // The page usually goes on to sign-in, which ends this timer; if it stays (the login page), the limit still holds.
  setTimeout(() => forgetStaleUnsentReport(null, storage), UNSENT_REPORT_KEPT_MS + 1_000);
}

/**
 * Reads the kept report without removing it, so a render React throws away loses nothing. The form removes it with
 * `forgetUnsentReport` once it is on screen. Another person's is never returned, nor an expired one.
 */
export function peekUnsentReport(
  sub: string,
  storage: DraftStorage | null = tabStorage(),
  now: number = Date.now(),
): UnsentReport | null {
  const kept = read(storage);
  if (!kept || kept.sub !== sub || isExpired(kept, now)) return null;
  return { buildingId: kept.buildingId, areaId: kept.areaId, text: kept.text };
}

/** Removes the kept report: the form has it on screen, or the person signed out. */
export function forgetUnsentReport(storage: DraftStorage | null = tabStorage()): void {
  remove(storage);
}

/**
 * Called whenever the app learns who is signed in, also when nobody is (`signedIn` null): an expired report is
 * removed, and so is one kept for someone other than the person now signed in. Signed out, an author's report that has
 * not expired stays, because they are on their way to sign in again.
 */
export function forgetStaleUnsentReport(
  signedIn: string | null,
  storage: DraftStorage | null = tabStorage(),
  now: number = Date.now(),
): void {
  const kept = read(storage);
  if (kept && (isExpired(kept, now) || (signedIn !== null && kept.sub !== signedIn))) remove(storage);
}
