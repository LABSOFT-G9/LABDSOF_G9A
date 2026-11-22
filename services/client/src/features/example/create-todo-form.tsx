import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import { toast } from "sonner";
import { useServices } from "@/app/context";
import { CreateTodoSchema } from "@/contracts/example";
import { createTodoMutation } from "@/features/example/api";
import { ErrorState } from "@/shared/components/error-state";
import { SubmitButton } from "@/shared/components/submit-button";
import { t } from "@/shared/i18n";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";

/** A form validated by the same zod contract the adapter sends, with errors tied to the field (NFR-07). */
export function CreateTodoForm() {
  const { example } = useServices();
  const queryClient = useQueryClient();
  const mutation = useMutation(createTodoMutation(example, queryClient));
  const [title, setTitle] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const inputId = useId();
  const hintId = useId();
  const errorId = useId();

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const parsed = CreateTodoSchema.safeParse({ title });
    if (!parsed.success) {
      setFieldError(parsed.error.issues[0]?.message ?? null);
      return;
    }
    setFieldError(null);
    mutation.mutate(parsed.data, {
      onSuccess: () => {
        setTitle("");
        toast.success(t.example.created);
      },
    });
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-3">
      <div className="space-y-2">
        <Label htmlFor={inputId}>{t.example.titleLabel}</Label>
        <Input
          id={inputId}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          aria-invalid={fieldError ? true : undefined}
          aria-describedby={fieldError ? `${hintId} ${errorId}` : hintId}
          autoComplete="off"
        />
        <p id={hintId} className="text-muted-foreground text-xs">
          {t.example.titleHint}
        </p>
        {fieldError ? (
          <p id={errorId} className="text-destructive text-sm" role="alert">
            {fieldError}
          </p>
        ) : null}
      </div>
      {mutation.isError ? <ErrorState error={mutation.error} /> : null}
      <SubmitButton pending={mutation.isPending} pendingLabel={t.example.creating}>
        {t.example.create}
      </SubmitButton>
    </form>
  );
}
