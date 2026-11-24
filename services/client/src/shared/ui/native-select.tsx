import type * as React from "react";
import { cn } from "@/shared/lib/utils";

/**
 * A native `<select>` with the look of `Input`. Native keeps keyboard, screen-reader and mobile pickers working
 * without any script.
 */
function NativeSelect({ className, ...props }: React.ComponentProps<"select">) {
  return (
    <select
      data-slot="native-select"
      className={cn(
        "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none md:text-sm dark:bg-input/30",
        "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50",
        "disabled:cursor-not-allowed disabled:opacity-50",
        "aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40",
        className,
      )}
      {...props}
    />
  );
}

export { NativeSelect };
