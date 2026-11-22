/** Time as a port, so tests and mocks can fix "now" (ADR-009). */
export interface Clock {
  now(): Date;
}
