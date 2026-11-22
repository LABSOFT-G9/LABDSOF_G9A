import { useMutation } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { ChevronRight, CloudOff, LogIn, LogOut } from "lucide-react";
import { useAppContext } from "@/app/context";
import type { AuthState } from "@/features/auth/session";
import { signOut, startSession } from "@/features/auth/session";
import { DocumentTitle } from "@/shared/components/document-title";
import { ErrorState } from "@/shared/components/error-state";
import { Logo } from "@/shared/components/logo";
import { t } from "@/shared/i18n";
import { Alert, AlertDescription } from "@/shared/ui/alert";
import { Button } from "@/shared/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/shared/ui/card";
import { Spinner } from "@/shared/ui/spinner";

/**
 * Mock wiring: pick a synthetic persona. HTTP wiring: one button that leaves for the edge's OIDC login (ADR-005);
 * Keycloak owns the credentials, so there is never a password field here.
 */
export function LoginPage({ auth, redirectTo }: { auth: AuthState; redirectTo: string }) {
  const context = useAppContext();
  const { services } = context;
  const router = useRouter();

  const signIn = useMutation({
    mutationFn: async (personaId: string | null) => {
      if (personaId && services.devPersonas) {
        await services.devPersonas.signInAs(personaId);
        await startSession(context);
        // `href`, not `to`: the return target may carry ?query and #hash.
        await router.navigate({ href: redirectTo, replace: true });
      } else {
        // The HTTP adapter leaves the page and never settles, so the button stays "Signing in…" until it unloads.
        await services.auth.login(redirectTo);
      }
    },
  });
  const leave = useMutation({
    mutationFn: async () => {
      await signOut(context);
      await router.invalidate(); // mock adapter only: the HTTP one has left the page
    },
  });

  const personas = services.devPersonas?.list() ?? [];

  return (
    <main id="main" className="flex min-h-dvh items-center justify-center p-4 pt-safe pb-safe">
      <DocumentTitle title={t.auth.signIn} />
      <div className="w-full max-w-md space-y-6">
        <div className="flex flex-col items-center gap-3 text-center">
          <Logo className="size-12" />
          <h1 className="text-2xl font-semibold">{t.auth.title}</h1>
          <p className="text-muted-foreground text-sm">{t.auth.subtitle}</p>
        </div>

        {auth.kind === "unreachable" ? (
          <Alert role="status">
            <CloudOff aria-hidden />
            <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
              {t.auth.unreachable}
              <Button size="sm" variant="outline" onClick={() => void router.invalidate()}>
                {t.common.retry}
              </Button>
            </AlertDescription>
          </Alert>
        ) : null}

        {auth.kind === "failed" ? (
          // The session could not be read (e.g. /me broke its contract): let the user retry, or sign out and switch.
          <div className="space-y-3">
            <ErrorState error={auth.error} onRetry={() => void router.invalidate()} />
            <Button variant="outline" className="w-full" disabled={leave.isPending} onClick={() => leave.mutate()}>
              <LogOut aria-hidden />
              {t.auth.signOut}
            </Button>
          </div>
        ) : null}

        {signIn.isError ? <ErrorState error={signIn.error} /> : null}

        {auth.kind === "failed" ? null : personas.length > 0 ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t.auth.personasTitle}</CardTitle>
              <CardDescription>{t.auth.personasHint}</CardDescription>
            </CardHeader>
            <CardContent>
              <ul className="divide-border -mx-2 divide-y">
                {personas.map((persona) => (
                  <li key={persona.id}>
                    <button
                      type="button"
                      disabled={signIn.isPending}
                      onClick={() => signIn.mutate(persona.id)}
                      className="hover:bg-accent focus-visible:ring-ring/50 flex min-h-14 w-full items-center gap-3 rounded-md px-2 py-3 text-left outline-none focus-visible:ring-[3px] disabled:opacity-50"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-medium">{persona.label}</span>
                        <span className="text-muted-foreground block text-xs">{persona.description}</span>
                      </span>
                      {signIn.isPending && signIn.variables === persona.id ? (
                        <Spinner aria-label={t.auth.signingIn} />
                      ) : (
                        <ChevronRight className="text-muted-foreground size-4" aria-hidden />
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        ) : (
          <Button className="h-11 w-full" disabled={signIn.isPending} onClick={() => signIn.mutate(null)}>
            {signIn.isPending ? <Spinner aria-hidden /> : <LogIn aria-hidden />}
            {signIn.isPending ? t.auth.signingIn : t.auth.signIn}
          </Button>
        )}
      </div>
    </main>
  );
}
