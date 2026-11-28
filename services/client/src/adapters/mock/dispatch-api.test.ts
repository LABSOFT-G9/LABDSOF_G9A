import { describe, expect, it } from "vitest";
import { createMockIdentity } from "@/adapters/mock/auth-session";
import { mockDispatchApi } from "@/adapters/mock/dispatch-api";
import { createMockRuntime } from "@/adapters/mock/runtime";
import { type ApiError } from "@/shared/lib/errors";

const runtime = createMockRuntime({ latencyMs: 0, allowFailures: false, readSearch: () => "" });
const now = new Date("2026-11-28T12:00:00Z");
const clock = { now: () => now };
const UNIT_2B = "0199f2a1-0000-7000-8000-000000002003";
const ROOF = "0199f2a1-0000-7000-8000-000000002004";
const LIFT = "0199f2a1-0000-7000-8000-000000002006";
const inHours = (hours: number) => new Date(now.getTime() + hours * 3_600_000).toISOString();
const confirmation = {
  window: { start: inHours(30), end: inHours(32) },
  source: "email" as const,
  reason: "Agreed by email with the contractor.",
};

function world(persona: string | null = "administrator-a") {
  const identity = createMockIdentity();
  identity.set(persona);
  return mockDispatchApi(identity, runtime, clock, true);
}

const rejection = (promise: Promise<unknown>) =>
  promise.then(
    () => null,
    (error: ApiError) => error,
  );

describe("mockDispatchApi (same rules as the Dispatch service)", () => {
  it("shows an issue's interventions to its building's administrators only", async () => {
    await expect(world().listInterventions(UNIT_2B)).resolves.toMatchObject({ items: [{ state: "booked" }] });
    for (const persona of ["administrator-b", "resident-a1", "contractor"]) {
      await expect(rejection(world(persona).listInterventions(UNIT_2B))).resolves.toMatchObject({ status: 404 });
    }
    await expect(rejection(world(null).listInterventions(UNIT_2B))).resolves.toMatchObject({ status: 401 });
    await expect(world("administrator-b").listInterventions(ROOF)).resolves.toMatchObject({
      items: [{ booking: { state: "confirmed", source: "calendar" } }],
    });
  });

  it("keeps every booking attempt, the current one last, and never sends the building", async () => {
    const [lift] = (await world().listInterventions(LIFT)).items;
    expect(lift!.state).toBe("scheduling_pending");
    expect(lift!.bookings.map((b) => [b.state, b.reasonCode])).toEqual([
      ["rejected", "slot_conflict"],
      ["failed", "retries_exhausted"],
    ]);
    expect(lift!.booking).toEqual(lift!.bookings[1]);
    expect(lift).not.toHaveProperty("buildingId");
  });

  it("confirms by hand once, and replays the answer for the same key", async () => {
    const api = world();
    const [lift] = (await api.listInterventions(LIFT)).items;
    const answer = await api.confirmManually(lift!.interventionId, lift!.version, confirmation, "k1");
    expect(answer).toEqual({
      interventionId: lift!.interventionId,
      state: "booked",
      version: 5,
      bookingState: "confirmed",
    });
    await expect(api.confirmManually(lift!.interventionId, lift!.version, confirmation, "k1")).resolves.toEqual(answer);

    const [booked] = (await api.listInterventions(LIFT)).items;
    expect(booked!.booking).toMatchObject({
      state: "confirmed",
      source: "manual",
      bookedWindow: confirmation.window,
      reasonCode: null,
      manual: { source: "email", reason: confirmation.reason },
    });
    expect(booked!.bookings).toHaveLength(2);
  });

  it("checks the fields first, then the version, then the state", async () => {
    const api = world();
    const [lift] = (await api.listInterventions(LIFT)).items;
    const id = lift!.interventionId;
    const invalid = await rejection(
      api.confirmManually(
        id,
        lift!.version,
        { window: { start: inHours(-1), end: inHours(-2) }, source: "" as never, reason: " " },
        "k2",
      ),
    );
    expect(invalid).toMatchObject({ status: 400, code: "VALIDATION_FAILED" });
    expect((invalid as ApiError).extensions["errors"]).toEqual([
      { field: "window.start", reason: "not_in_future" },
      { field: "window.end", reason: "not_after_start" },
      { field: "source", reason: "required" },
      { field: "reason", reason: "required" },
    ]);

    await expect(rejection(api.confirmManually(id, 3, confirmation, "k3"))).resolves.toMatchObject({
      status: 409,
      code: "VERSION_CONFLICT",
      extensions: { currentVersion: 4, currentState: "scheduling_pending" },
    });

    const [unit] = (await api.listInterventions(UNIT_2B)).items;
    await expect(
      rejection(api.confirmManually(unit!.interventionId, unit!.version, confirmation, "k4")),
    ).resolves.toMatchObject({ status: 409, code: "INVALID_TRANSITION", extensions: { currentState: "booked" } });
  });
});
