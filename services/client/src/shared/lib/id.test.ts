import { expect, it } from "vitest";
import { randomId } from "@/shared/lib/id";

it("makes RFC 9562 version-4 UUIDs", () => {
  const ids = new Set(Array.from({ length: 100 }, randomId));
  expect(ids.size).toBe(100);
  for (const id of ids) expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
