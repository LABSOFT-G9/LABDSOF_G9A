import { cn } from "@/shared/lib/utils";

/** Placeholder mark, same drawing as public/favicon.svg. Inverts with the theme. */
export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 512 512" aria-hidden className={cn("size-7", className)}>
      <rect width="512" height="512" rx="112" className="fill-foreground" />
      <path
        className="fill-background"
        d="M176 128h112a64 64 0 0 1 40 114 72 72 0 0 1-32 142H176zm48 48v56h60a28 28 0 0 0 0-56zm0 104v56h72a28 28 0 0 0 0-56z"
      />
    </svg>
  );
}
