import { useQuery } from "@tanstack/react-query";
import {
  CalendarCheck,
  CalendarClock,
  CalendarX,
  CircleHelp,
  CircleSlash,
  Clock,
  type LucideIcon,
  TriangleAlert,
} from "lucide-react";
import { useServices } from "@/app/context";
import type { Booking, BookingState, Intervention } from "@/contracts/dispatch";
import { issueInterventionsQuery } from "@/features/interventions/api";
import { ManualConfirmationForm } from "@/features/interventions/manual-confirmation-form";
import { ErrorState } from "@/shared/components/error-state";
import { t } from "@/shared/i18n";
import { formatDateTime, timeAgo } from "@/shared/lib/format";
import { Alert, AlertDescription, AlertTitle } from "@/shared/ui/alert";
import { Badge } from "@/shared/ui/badge";
import { Card, CardContent, CardHeader } from "@/shared/ui/card";
import { Separator } from "@/shared/ui/separator";
import { Skeleton } from "@/shared/ui/skeleton";

const ICONS: Record<BookingState, LucideIcon> = {
  requested: Clock,
  uncertain: CircleHelp,
  confirmed: CalendarCheck,
  rejected: CalendarX,
  failed: CalendarX,
  discrepancy: TriangleAlert,
  cancelled: CircleSlash,
};

/** Booking state as icon + text, never colour alone (NFR-07). */
export function BookingStateBadge({ state }: { state: BookingState }) {
  const Icon = ICONS[state];
  return (
    <Badge variant={state === "confirmed" ? "secondary" : "outline"}>
      <Icon aria-hidden />
      {t.booking.states[state]}
    </Badge>
  );
}

const reasonText = (code: string) => t.booking.reasons[code] ?? t.booking.unknownReason(code);

function sourceText(booking: Booking): string {
  if (booking.source === "manual" && booking.manual) {
    return t.booking.manualSource(t.booking.manualSources[booking.manual.source]);
  }
  return booking.source ? t.booking.sources[booking.source] : t.booking.notConfirmedYet;
}

const windowText = ({ start, end }: { start: string; end: string }) =>
  `${formatDateTime(start)} – ${formatDateTime(end)}`;

/** One booking: its own state and source (kept apart from the intervention's), the window, and why it failed. */
function BookingDetails({ booking, now }: { booking: Booking; now: Date }) {
  const hint = t.booking.stateHints[booking.state];
  const moved =
    booking.bookedWindow.start !== booking.requestedWindow.start ||
    booking.bookedWindow.end !== booking.requestedWindow.end;
  return (
    <dl className="grid gap-3 text-sm sm:grid-cols-[auto_1fr] sm:gap-x-6">
      <dt className="text-muted-foreground">{t.booking.status}</dt>
      <dd className="space-y-1">
        <BookingStateBadge state={booking.state} />
        {hint ? <p className="text-muted-foreground">{hint}</p> : null}
        {booking.pendingAction ? (
          <p className="flex items-center gap-2">
            <CalendarClock className="size-4" aria-hidden />
            {t.booking.pendingActions[booking.pendingAction]}
          </p>
        ) : null}
      </dd>
      <dt className="text-muted-foreground">{t.booking.source}</dt>
      <dd>{sourceText(booking)}</dd>
      <dt className="text-muted-foreground">{t.booking.window}</dt>
      <dd>{windowText(booking.bookedWindow)}</dd>
      {moved ? (
        <>
          <dt className="text-muted-foreground">{t.booking.requestedWindow}</dt>
          <dd>{windowText(booking.requestedWindow)}</dd>
        </>
      ) : null}
      {booking.reasonCode ? (
        <>
          <dt className="text-muted-foreground">{t.booking.reason}</dt>
          <dd>{reasonText(booking.reasonCode)}</dd>
        </>
      ) : null}
      {booking.externalRef ? (
        <>
          <dt className="text-muted-foreground">{t.booking.reference}</dt>
          <dd className="font-mono text-xs">{booking.externalRef}</dd>
        </>
      ) : null}
      {booking.manual ? (
        <>
          <dt className="text-muted-foreground">{t.booking.confirmedByHandLabel}</dt>
          <dd className="space-y-1">
            <time dateTime={booking.manual.confirmedAt}>{timeAgo(booking.manual.confirmedAt, now)}</time>
            {booking.manual.reason ? (
              <p className="break-words whitespace-pre-wrap">
                <span className="text-muted-foreground">{t.booking.reasonLabel}: </span>
                {booking.manual.reason}
              </p>
            ) : null}
          </dd>
        </>
      ) : null}
      {booking.lastReconciledAt ? (
        <>
          <dt className="text-muted-foreground">{t.booking.lastCheckedLabel}</dt>
          <dd>
            <time dateTime={booking.lastReconciledAt}>{timeAgo(booking.lastReconciledAt, now)}</time>
          </dd>
        </>
      ) : null}
    </dl>
  );
}

function Panel({ intervention, now }: { intervention: Intervention; now: Date }) {
  const booking = intervention.booking;
  const earlier = intervention.bookings.filter((b) => b.bookingId !== booking?.bookingId).reverse();
  return (
    <div className="space-y-4">
      <p className="text-sm">
        <span className="text-muted-foreground">{t.booking.contractor}: </span>
        {intervention.contractor.displayName}
      </p>

      {intervention.state === "cancellation_pending" ? (
        <Alert>
          <Clock aria-hidden />
          <AlertTitle>{t.booking.cancellationPendingTitle}</AlertTitle>
          <AlertDescription>{t.booking.cancellationPendingBody}</AlertDescription>
        </Alert>
      ) : null}

      {booking?.state === "discrepancy" ? (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden />
          <AlertTitle>{t.booking.discrepancyTitle}</AlertTitle>
          <AlertDescription>
            {booking.reasonCode ? reasonText(booking.reasonCode) : t.booking.stateHints.discrepancy}
          </AlertDescription>
        </Alert>
      ) : null}

      {booking ? (
        <BookingDetails booking={booking} now={now} />
      ) : (
        <p className="text-muted-foreground text-sm">{t.booking.noBooking}</p>
      )}

      {earlier.length > 0 ? (
        <section className="space-y-2">
          <h3 className="text-sm font-medium">{t.booking.earlier}</h3>
          <ul className="space-y-2 text-sm">
            {earlier.map((attempt) => (
              <li key={attempt.bookingId} className="flex flex-wrap items-center gap-2">
                <BookingStateBadge state={attempt.state} />
                <time dateTime={attempt.updatedAt} className="text-muted-foreground">
                  {formatDateTime(attempt.updatedAt)}
                </time>
                {attempt.reasonCode ? <span>{reasonText(attempt.reasonCode)}</span> : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {intervention.state === "scheduling_pending" && booking ? (
        <>
          <Separator />
          <ManualConfirmationForm key={intervention.interventionId} intervention={intervention} booking={booking} />
        </>
      ) : null}
    </div>
  );
}

/**
 * US-08: where the booking of the issue's intervention stands, for the building's administrators. The booking's state
 * and source are shown apart from the intervention's, with the reason of a rejection or failure; a discrepancy and a
 * pending cancellation are called out. While the intervention waits for a booking, the administrator can confirm it
 * by hand. Shown only once the newest intervention has a booking or waits for one: before that, the approval screens
 * (US-07) are the place.
 */
export function BookingPanel({ issueId }: { issueId: string }) {
  const { dispatch, clock } = useServices();
  const query = useQuery(issueInterventionsQuery(dispatch, issueId));
  const intervention = query.data?.items[0];

  if (query.isPending) {
    return (
      <Card>
        <CardContent>
          <div role="status" className="space-y-2">
            <span className="sr-only">{t.common.loading}</span>
            <Skeleton className="h-6 w-1/3" />
            <Skeleton className="h-16 w-full" />
          </div>
        </CardContent>
      </Card>
    );
  }
  if (query.isError) {
    return (
      <Card>
        <CardHeader>
          <h2 className="text-base font-semibold">{t.booking.title}</h2>
        </CardHeader>
        <CardContent>
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        </CardContent>
      </Card>
    );
  }
  if (!intervention || (!intervention.booking && intervention.state !== "scheduling_pending")) return null;

  return (
    <Card>
      <CardHeader>
        <h2 className="text-base font-semibold">{t.booking.title}</h2>
      </CardHeader>
      <CardContent>
        <Panel intervention={intervention} now={clock.now()} />
      </CardContent>
    </Card>
  );
}
