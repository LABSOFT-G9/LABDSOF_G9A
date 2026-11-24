import { describe, expect, it } from "vitest";
import { SubmitReportSchema } from "@/contracts/issue";

const valid = { buildingId: "b", areaId: "a", text: "The garage door does not close." };
const reasons = (input: unknown) => {
  const result = SubmitReportSchema.safeParse(input);
  return result.success ? [] : result.error.issues.map((issue) => `${String(issue.path[0])}:${issue.message}`);
};

/** The same rules as the Issue service's `ReportText` and request validation (US-01-01). */
describe("SubmitReportSchema", () => {
  it("accepts a valid report and trims the text", () => {
    expect(SubmitReportSchema.parse({ ...valid, text: "  Water on the floor  " }).text).toBe("Water on the floor");
  });

  it("requires a building, an area and a text", () => {
    expect(reasons({ buildingId: "", areaId: "", text: "   " })).toEqual([
      "buildingId:required",
      "areaId:required",
      "text:required",
    ]);
  });

  it("counts code points, so ten emoji are ten characters", () => {
    expect(reasons({ ...valid, text: "😀".repeat(10) })).toEqual([]);
    expect(reasons({ ...valid, text: "😀".repeat(9) })).toEqual(["text:too_short"]);
  });

  it("allows 4,000 characters and refuses 4,001", () => {
    expect(reasons({ ...valid, text: "x".repeat(4_000) })).toEqual([]);
    expect(reasons({ ...valid, text: "x".repeat(4_001) })).toEqual(["text:too_long"]);
  });

  it("allows tabs and line breaks but refuses other control characters and lone surrogates", () => {
    expect(reasons({ ...valid, text: "Line one\nline two\tand\r\nthree" })).toEqual([]);
    expect(reasons({ ...valid, text: "Bell \u0007 in the text" })).toEqual(["text:invalid_characters"]);
    expect(reasons({ ...valid, text: "C1 control \u0085 here" })).toEqual(["text:invalid_characters"]);
    expect(reasons({ ...valid, text: "Broken \uD800 character" })).toEqual(["text:invalid_characters"]);
  });
});
