import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useId, useState } from "react";
import { toast } from "sonner";
import { useServices } from "@/app/context";
import { type CreatePerson, CreatePersonSchema } from "@/contracts/example";
import { createPersonMutation } from "@/features/example/api";
import { ErrorState } from "@/shared/components/error-state";
import { SubmitButton } from "@/shared/components/submit-button";
import { t } from "@/shared/i18n";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";

type Field = keyof CreatePerson;
const EMPTY: CreatePerson = { firstName: "", lastName: "", email: "" };

/** A form validated by the same zod contract the adapter sends, with each error tied to its field (NFR-07). */
export function CreatePersonForm() {
  const { example } = useServices();
  const queryClient = useQueryClient();
  const mutation = useMutation(createPersonMutation(example, queryClient));
  const [values, setValues] = useState<CreatePerson>(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const id = useId();

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const parsed = CreatePersonSchema.safeParse(values);
    if (!parsed.success) {
      const next: Partial<Record<Field, string>> = {};
      for (const issue of parsed.error.issues) next[issue.path[0] as Field] ??= issue.message;
      setErrors(next);
      // Focus the first invalid field, so keyboard and screen-reader users land on the problem.
      const first = (["firstName", "lastName", "email"] as const).find((field) => next[field]);
      if (first) document.getElementById(`${id}-${first}`)?.focus();
      return;
    }
    setErrors({});
    mutation.mutate(parsed.data, {
      onSuccess: (person) => {
        setValues(EMPTY);
        toast.success(t.example.created(person.name));
      },
    });
  }

  const field = (name: Field, label: string, type = "text", autoComplete?: string) => (
    <div className="space-y-2">
      <Label htmlFor={`${id}-${name}`}>{label}</Label>
      <Input
        id={`${id}-${name}`}
        type={type}
        value={values[name]}
        onChange={(event) => setValues((v) => ({ ...v, [name]: event.target.value }))}
        aria-invalid={errors[name] ? true : undefined}
        aria-describedby={errors[name] ? `${id}-${name}-error` : undefined}
        autoComplete={autoComplete}
      />
      {errors[name] ? (
        <p id={`${id}-${name}-error`} className="text-destructive text-sm">
          {errors[name]}
        </p>
      ) : null}
    </div>
  );

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {field("firstName", t.example.firstName, "text", "off")}
      {field("lastName", t.example.lastName, "text", "off")}
      {field("email", t.example.email, "email", "off")}
      {mutation.isError ? <ErrorState error={mutation.error} /> : null}
      <SubmitButton pending={mutation.isPending} pendingLabel={t.example.creating}>
        {t.example.create}
      </SubmitButton>
    </form>
  );
}
