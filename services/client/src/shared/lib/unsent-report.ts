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

/**
 * Keeps a report the service refused with `401` (the session ended while it was being written), so the text is not
 * lost on the way through sign-in. It stays in this tab only, under the author's `sub`, for at most 15 minutes, and
 * is removed when the form takes it back or when anyone else signs in (`forgetUnsentReportOfOthers`). Storage
 * blocked: the text is lost, as before.
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
  }
}

/** Reads and forgets the kept report. Another person's is never returned, nor one kept too long ago. */
export function takeUnsentReport(
  sub: string,
  storage: DraftStorage | null = tabStorage(),
  now: number = Date.now(),
): UnsentReport | null {
  const kept = read(storage);
  remove(storage);
  if (!kept || kept.sub !== sub) return null;
  if (now - kept.keptAt > UNSENT_REPORT_KEPT_MS || now < kept.keptAt) return null;
  return { buildingId: kept.buildingId, areaId: kept.areaId, text: kept.text };
}

/** Called as soon as the signed-in person is known: a report kept for someone else, or too long ago, is removed. */
export function forgetUnsentReportOfOthers(
  sub: string,
  storage: DraftStorage | null = tabStorage(),
  now: number = Date.now(),
): void {
  const kept = read(storage);
  if (kept && (kept.sub !== sub || now - kept.keptAt > UNSENT_REPORT_KEPT_MS)) remove(storage);
}
