import { KNOWN_SIGNAL_CODES, type KnownSignalCode } from "@/contracts/issue";
import { t } from "@/shared/i18n";

const isKnownSignal = (code: string): code is KnownSignalCode =>
  (KNOWN_SIGNAL_CODES as readonly string[]).includes(code);

/** A critical-check signal's rule code in words; an unknown code in general words (#96). */
export const signalText = (code: string) => t.issues.critical.signals[isKnownSignal(code) ? code : "other"];
