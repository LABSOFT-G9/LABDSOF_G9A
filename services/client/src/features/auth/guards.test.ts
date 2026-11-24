import { isNotFound, isRedirect } from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { PERSONAS } from "@/adapters/mock/data";
import { hasBuildingRole, requirePlatformRole, requireSession } from "@/features/auth/guards";

const persona = (id: string) => PERSONAS.find((p) => p.id === id)!.session;
const catchThrown = (fn: () => unknown) => {
  try {
    fn();
  } catch (thrown) {
    return thrown;
  }
  return undefined;
};

describe("guards", () => {
  it("requireSession returns the session when signed in", () => {
    const session = persona("resident-a1");
    expect(requireSession({ context: { auth: { kind: "signed-in", session } }, location: { href: "/x" } })).toBe(
      session,
    );
  });

  it("requireSession redirects to /login with the return path otherwise", () => {
    const thrown = catchThrown(() =>
      requireSession({ context: { auth: { kind: "anonymous" } }, location: { href: "/issues?x=1" } }),
    );
    expect(isRedirect(thrown)).toBe(true);
    expect((thrown as { options: unknown }).options).toMatchObject({
      to: "/login",
      search: { redirect: "/issues?x=1" },
    });
  });

  it("requirePlatformRole answers not-found, never forbidden", () => {
    expect(isNotFound(catchThrown(() => requirePlatformRole(persona("administrator-a"), "platform_staff")))).toBe(true);
    expect(catchThrown(() => requirePlatformRole(persona("platform-staff"), "platform_staff"))).toBeUndefined();
  });

  it("hasBuildingRole is per building", () => {
    const resident = persona("resident-a1");
    const building = resident.memberships[0]!.buildingId;
    expect(hasBuildingRole(resident, building, "resident")).toBe(true);
    expect(hasBuildingRole(resident, building, "administrator")).toBe(false);
    expect(hasBuildingRole(resident, "another-building", "resident")).toBe(false);
  });
});
