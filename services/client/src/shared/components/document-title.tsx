import { t } from "@/shared/i18n";

/** Sets the tab title to "<page> · BMC". React 19 hoists <title> into <head> from anywhere in the tree. */
export function DocumentTitle({ title }: { title: string }) {
  return <title>{`${title} · ${t.app.name}`}</title>;
}
