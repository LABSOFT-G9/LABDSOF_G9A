import type { MockIdentity } from "@/adapters/mock/auth-session";
import { BUILDINGS } from "@/adapters/mock/data";
import type { MockRuntime } from "@/adapters/mock/runtime";
import {
  type Booking,
  type BookingCommandResult,
  type Intervention,
  MANUAL_REASON_MAX,
  ManualConfirmationSchema,
  type Window,
} from "@/contracts/dispatch";
import { codePointLength } from "@/contracts/issue";
import type { Session } from "@/ports/auth-session";
import type { Clock } from "@/ports/clock";
import type { DispatchApi } from "@/ports/dispatch-api";
import { ApiError } from "@/shared/lib/errors";

/** An intervention as the mock Dispatch service stores it: with its building, which the answer does not carry. */
interface InterventionRecord extends Intervention {
  readonly buildingId: string;
}

const interventionId = (suffix: string) => `0199f2a2-0000-7000-8000-${suffix}`;
const bookingId = (suffix: string) => `0199f2a3-0000-7000-8000-${suffix}`;
const issueId = (suffix: string) => `0199f2a1-0000-7000-8000-${suffix}`;
const CONTRACTOR = {
  contractorId: "0199f2a0-0000-7000-8000-000000000401",
  displayName: "Synthetic Plumbing Co.",
  synthetic: true,
};

const notFound = () => new ApiError(404, "NOT_FOUND", "Not found.", "mock-correlation-id");

/**
 * The bookings of the mock issues (`seedIssues`): "Unit 2B" confirmed by hand after a failed request, "Roof terrace"
 * confirmed by the calendar, and "Lift lobby" waiting for a manual confirmation after a conflict and exhausted retries.
 */
export function seedInterventions(now: Date): InterventionRecord[] {
  const at = (hours: number) => new Date(now.getTime() - hours * 3_600_000).toISOString();
  const window = (inHours: number): Window => ({
    start: new Date(now.getTime() + inHours * 3_600_000).toISOString(),
    end: new Date(now.getTime() + (inHours + 2) * 3_600_000).toISOString(),
  });
  const booking = (suffix: string, rest: Partial<Booking> & Pick<Booking, "state">): Booking => ({
    bookingId: bookingId(suffix),
    source: null,
    requestedWindow: window(48),
    bookedWindow: window(48),
    externalRef: null,
    reasonCode: null,
    pendingAction: null,
    lastReconciledAt: null,
    manual: null,
    calls: [],
    createdAt: at(50),
    updatedAt: at(40),
    ...rest,
  });
  const record = (rest: Omit<InterventionRecord, "bookings" | "contractor" | "cancellation">): InterventionRecord => ({
    contractor: CONTRACTOR,
    cancellation: null,
    ...rest,
    bookings: rest.booking ? [rest.booking] : [],
  });

  const unitBooking = booking("000000000001", {
    state: "confirmed",
    source: "manual",
    bookedWindow: window(46),
    manual: { source: "phone", reason: "Agreed by phone after the calendar was unavailable.", confirmedAt: at(40) },
    calls: [
      { action: "create", outcome: "unavailable", at: at(49) },
      { action: "create", outcome: "unavailable", at: at(48) },
    ],
  });
  const roofBooking = booking("000000000002", {
    state: "confirmed",
    source: "calendar",
    requestedWindow: window(-2),
    bookedWindow: window(-2),
    externalRef: "CAL-SYN-0002",
    lastReconciledAt: at(3),
    calls: [{ action: "create", outcome: "confirmed", at: at(26) }],
    createdAt: at(27),
    updatedAt: at(26),
  });
  const liftRejected = booking("000000000003", {
    state: "rejected",
    reasonCode: "slot_conflict",
    calls: [{ action: "create", outcome: "conflict", at: at(16.5) }],
    createdAt: at(17),
    updatedAt: at(16.5),
  });
  const liftFailed = booking("000000000004", {
    state: "failed",
    reasonCode: "retries_exhausted",
    calls: [
      { action: "create", outcome: "unavailable", at: at(2) },
      { action: "create", outcome: "unavailable", at: at(1.9) },
      { action: "create", outcome: "unavailable", at: at(1.8) },
      { action: "create", outcome: "unavailable", at: at(1.6) },
      { action: "create", outcome: "unavailable", at: at(1.3) },
    ],
    createdAt: at(2),
    updatedAt: at(1.3),
  });

  return [
    record({
      interventionId: interventionId("000000000001"),
      issueId: issueId("000000002003"),
      buildingId: BUILDINGS.a.buildingId,
      state: "booked",
      version: 5,
      agreedProposalVersion: 1,
      booking: unitBooking,
      createdAt: at(62),
      updatedAt: at(40),
    }),
    record({
      interventionId: interventionId("000000000002"),
      issueId: issueId("000000002004"),
      buildingId: BUILDINGS.b.buildingId,
      state: "in_progress",
      version: 6,
      agreedProposalVersion: 1,
      booking: roofBooking,
      createdAt: at(28.5),
      updatedAt: at(2),
    }),
    {
      ...record({
        interventionId: interventionId("000000000003"),
        issueId: issueId("000000002006"),
        buildingId: BUILDINGS.a.buildingId,
        state: "scheduling_pending",
        version: 4,
        agreedProposalVersion: 1,
        booking: liftFailed,
        createdAt: at(18.5),
        updatedAt: at(1.3),
      }),
      bookings: [liftRejected, liftFailed],
    },
  ];
}

/**
 * In-memory Dispatch service for the booking screens (US-08). Applies the same rules as the real one (services/dispatch
 * README, "API" and "Booking"): only an administrator of the issue's building sees its interventions (`404` for anyone
 * else), the manual confirmation is checked field by field (`400`), then against the version (`409
 * VERSION_CONFLICT`) and the state (`409 INVALID_TRANSITION`), and a repeated key replays the first answer.
 */
export function mockDispatchApi(
  identity: MockIdentity,
  runtime: MockRuntime,
  clock: Clock,
  /** Auth is mocked too: no persona means no session, as a real service would answer 401. */
  authIsMocked: boolean,
): DispatchApi {
  const interventions: InterventionRecord[] = seedInterventions(clock.now());
  const replays = new Map<string, { body: string; answer: BookingCommandResult }>();

  function caller(): Session | null {
    const me = identity.current();
    if (!me && authIsMocked) throw new ApiError(401, "UNAUTHENTICATED", "Sign in to continue.");
    return me;
  }
  /** `null` caller = no persona to apply rules to: treated as an administrator everywhere. */
  const administers = (me: Session | null, buildingId: string) =>
    !me || me.memberships.some((m) => m.buildingId === buildingId && m.role === "administrator");

  const project = ({ buildingId: _b, ...intervention }: InterventionRecord): Intervention => intervention;

  return {
    listInterventions: (issue) =>
      runtime.call(() => {
        const me = caller();
        const items = interventions.filter((i) => i.issueId === issue);
        if (items.length === 0 || !administers(me, items[0]!.buildingId)) throw notFound();
        return { items: items.sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(project) };
      }),

    confirmManually: (id, version, input, idempotencyKey) =>
      runtime.call(() => {
        const me = caller();
        const index = interventions.findIndex((i) => i.interventionId === id);
        const current = interventions[index];
        if (!current || !administers(me, current.buildingId)) throw notFound();

        const scopedKey = `${me?.sub ?? "anonymous"}:manual:${idempotencyKey}`;
        const body = JSON.stringify(input);
        const previous = replays.get(scopedKey);
        if (previous) {
          if (previous.body !== body) {
            throw new ApiError(422, "IDEMPOTENCY_KEY_REUSED", "This key was already used for a different request.");
          }
          return previous.answer;
        }

        const errors: { field: string; reason: string }[] = [];
        const parsed = ManualConfirmationSchema.safeParse(input);
        const start = Date.parse(input.window?.start ?? "");
        const end = Date.parse(input.window?.end ?? "");
        if (Number.isNaN(start)) errors.push({ field: "window.start", reason: "required" });
        else if (start <= clock.now().getTime()) errors.push({ field: "window.start", reason: "not_in_future" });
        if (Number.isNaN(end)) errors.push({ field: "window.end", reason: "required" });
        else if (!Number.isNaN(start) && end <= start) errors.push({ field: "window.end", reason: "not_after_start" });
        if (!parsed.success && parsed.error.issues.some((issue) => issue.path[0] === "source")) {
          errors.push({ field: "source", reason: input.source ? "invalid" : "required" });
        }
        const reason = (input.reason ?? "").trim();
        if (!reason) errors.push({ field: "reason", reason: "required" });
        else if (codePointLength(reason) > MANUAL_REASON_MAX) errors.push({ field: "reason", reason: "too_long" });
        if (errors.length > 0) {
          throw new ApiError(400, "VALIDATION_FAILED", "The request is not valid.", "mock-correlation-id", { errors });
        }

        if (version !== current.version) {
          throw new ApiError(409, "VERSION_CONFLICT", "The intervention changed.", "mock-correlation-id", {
            currentVersion: current.version,
            currentState: current.state,
          });
        }
        if (current.state !== "scheduling_pending") {
          throw new ApiError(409, "INVALID_TRANSITION", "Not allowed in this state.", "mock-correlation-id", {
            currentState: current.state,
          });
        }

        const now = clock.now().toISOString();
        const window = { start: new Date(start).toISOString(), end: new Date(end).toISOString() };
        const base = current.booking!;
        const confirmed: Booking = {
          ...base,
          state: "confirmed",
          source: "manual",
          bookedWindow: window,
          reasonCode: null,
          pendingAction: null,
          manual: { source: parsed.data!.source, reason, confirmedAt: now },
          updatedAt: now,
        };
        const next: InterventionRecord = {
          ...current,
          state: "booked",
          version: current.version + 1,
          booking: confirmed,
          bookings: [...current.bookings.slice(0, -1), confirmed],
          updatedAt: now,
        };
        interventions[index] = next;
        const answer: BookingCommandResult = {
          interventionId: id,
          state: next.state,
          version: next.version,
          bookingState: confirmed.state,
        };
        replays.set(scopedKey, { body, answer });
        return answer;
      }),
  };
}
