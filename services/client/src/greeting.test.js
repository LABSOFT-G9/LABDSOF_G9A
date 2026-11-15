import { expect, test } from "vitest";
import { greeting } from "./greeting.js";

test("greeting says the client is up", () => {
  expect(greeting()).toBe("client up");
});
