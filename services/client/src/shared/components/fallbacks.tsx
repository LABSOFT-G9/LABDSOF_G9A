import { type ErrorComponentProps, Link, useRouter } from "@tanstack/react-router";
import { SearchX } from "lucide-react";
import { ErrorState } from "@/shared/components/error-state";
import { t } from "@/shared/i18n";
import { Button } from "@/shared/ui/button";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";
import { Spinner } from "@/shared/ui/spinner";

/** Shown only when a route takes longer than `defaultPendingMs`. */
export function PendingFallback() {
  return (
    <div className="flex min-h-40 items-center justify-center" role="status">
      <Spinner className="size-5" aria-hidden />
      <span className="sr-only">{t.common.loading}</span>
    </div>
  );
}

/** A route's loader or component threw. Retry re-runs the loaders. */
export function ErrorFallback({ error, reset }: ErrorComponentProps) {
  const router = useRouter();
  return (
    <div className="mx-auto max-w-xl p-4">
      <ErrorState
        error={error}
        onRetry={() => {
          reset();
          void router.invalidate();
        }}
      />
    </div>
  );
}

export function NotFoundFallback() {
  return (
    <Empty className="min-h-[50vh]">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <SearchX aria-hidden />
        </EmptyMedia>
        <EmptyTitle>{t.common.notFoundTitle}</EmptyTitle>
        <EmptyDescription>{t.common.notFoundBody}</EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button asChild variant="outline">
          <Link to="/dashboard">{t.common.goHome}</Link>
        </Button>
      </EmptyContent>
    </Empty>
  );
}
