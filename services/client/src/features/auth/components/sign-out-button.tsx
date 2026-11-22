import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { LogOut } from "lucide-react";
import { useAppContext } from "@/app/context";
import { signOut } from "@/features/auth/session";
import { t } from "@/shared/i18n";
import { DropdownMenuItem } from "@/shared/ui/dropdown-menu";

/** A menu item: clears local data, ends the session (the BFF adapter leaves the page), then shows the login page. */
export function SignOutButton() {
  const context = useAppContext();
  const navigate = useNavigate();
  const mutation = useMutation({
    mutationFn: async () => {
      await signOut(context);
      await navigate({ to: "/login", search: { redirect: undefined }, replace: true });
    },
  });
  return (
    <DropdownMenuItem disabled={mutation.isPending} onSelect={() => mutation.mutate()}>
      <LogOut aria-hidden />
      {t.auth.signOut}
    </DropdownMenuItem>
  );
}
