import { useId, type ComponentProps } from "react";
import { useOnline } from "@/shared/hooks/use-online";
import { t } from "@/shared/i18n";
import { Button } from "@/shared/ui/button";
import { Spinner } from "@/shared/ui/spinner";

/**
 * Every form's submit button. Disabled while offline with the reason visible next to it (ADR-011), and while
 * `pending`, with a spinner.
 */
export function SubmitButton({
  pending = false,
  pendingLabel,
  children,
  disabled,
  ...props
}: ComponentProps<typeof Button> & { pending?: boolean; pendingLabel?: string }) {
  const online = useOnline();
  const reasonId = useId();
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button
        type="submit"
        disabled={disabled || pending || !online}
        aria-describedby={online ? undefined : reasonId}
        {...props}
      >
        {pending ? <Spinner aria-hidden /> : null}
        {pending && pendingLabel ? pendingLabel : children}
      </Button>
      {online ? null : (
        <p id={reasonId} className="text-muted-foreground text-sm">
          {t.common.offlineSubmit}
        </p>
      )}
    </div>
  );
}
