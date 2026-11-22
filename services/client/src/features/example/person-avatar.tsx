import type { Person } from "@/ports/example-api";
import { cn } from "@/shared/lib/utils";
import { Avatar, AvatarFallback, AvatarImage } from "@/shared/ui/avatar";

const initials = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");

/** Photo when there is one, initials otherwise (and while it loads). Decorative: the name is always next to it. */
export function PersonAvatar({ person, className }: { person: Person; className?: string }) {
  return (
    <Avatar className={cn("size-10", className)}>
      {person.avatarUrl ? <AvatarImage src={person.avatarUrl} alt="" /> : null}
      <AvatarFallback className="bg-secondary text-xs font-semibold" aria-hidden>
        {initials(person.name)}
      </AvatarFallback>
    </Avatar>
  );
}
