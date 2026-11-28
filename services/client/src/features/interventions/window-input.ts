const pad = (value: number) => String(value).padStart(2, "0");

/** An RFC 3339 instant as the value of a `datetime-local` input, in the browser's time zone. */
export function toLocalInput(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** A `datetime-local` value (the browser's time zone) as an RFC 3339 instant in UTC; `null` when empty or invalid. */
export function fromLocalInput(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
