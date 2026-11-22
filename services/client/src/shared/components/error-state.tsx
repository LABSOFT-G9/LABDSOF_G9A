import { CircleAlert, CloudOff, RotateCw } from "lucide-react";
import { t } from "@/shared/i18n";
import { ApiError, ContractError, isUnavailable, NetworkError } from "@/shared/lib/errors";
import { Alert, AlertDescription, AlertTitle } from "@/shared/ui/alert";
import { Button } from "@/shared/ui/button";

function describe(error: unknown): { title: string; detail: string; reference?: string | undefined } {
  if (error instanceof NetworkError) return { title: t.common.errorTitle, detail: t.auth.unreachable };
  if (error instanceof ApiError)
    return { title: t.common.errorTitle, detail: error.message, reference: error.correlationId };
  if (error instanceof ContractError) return { title: t.common.errorTitle, detail: error.message };
  return { title: t.common.errorTitle, detail: error instanceof Error ? error.message : String(error) };
}

/**
 * A failed request, explained in place: icon and text (never colour alone), the server's reference for support, and a
 * retry. `role="status"` announces it without moving focus (ADR-011).
 */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { title, detail, reference } = describe(error);
  const Icon = isUnavailable(error) ? CloudOff : CircleAlert;
  return (
    <Alert role="status" variant="destructive">
      <Icon aria-hidden />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>
        <p>{detail}</p>
        {reference ? <p className="text-xs opacity-80">{t.common.reference(reference)}</p> : null}
        {onRetry ? (
          <Button variant="outline" size="sm" className="mt-2" onClick={onRetry}>
            <RotateCw aria-hidden />
            {t.common.retry}
          </Button>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}
