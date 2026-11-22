import { z } from "zod";

/**
 * Error body of every BMC service: `application/problem+json` (RFC 9457) with the extension members `code` and
 * `correlationId`, plus `currentState` / `currentVersion` on conflicts (technical design §4.1).
 */
export const ProblemSchema = z.looseObject({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: z.string().optional(),
  correlationId: z.string().optional(),
  currentState: z.string().optional(),
  currentVersion: z.number().int().optional(),
});
export type Problem = z.infer<typeof ProblemSchema>;
