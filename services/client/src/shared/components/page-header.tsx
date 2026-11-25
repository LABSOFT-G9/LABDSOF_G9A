import type { ReactNode } from "react";
import { DocumentTitle } from "@/shared/components/document-title";

/**
 * The one <h1> of a page, with an optional subtitle and actions. Also sets the document title (WCAG 2.4.2); the
 * root route moves focus to this heading after each navigation, hence `tabIndex={-1}`.
 */
export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <header className="mb-8 flex flex-wrap items-end justify-between gap-4">
      <DocumentTitle title={title} />
      <div className="min-w-0 space-y-2">
        <h1 tabIndex={-1} className="text-[1.75rem] outline-none md:text-4xl">
          {title}
        </h1>
        {subtitle ? <p className="text-muted-foreground max-w-prose text-base">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
    </header>
  );
}
