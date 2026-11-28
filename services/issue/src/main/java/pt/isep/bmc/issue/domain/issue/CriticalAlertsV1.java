package pt.isep.bmc.issue.domain.issue;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * The rule set {@code critical-alerts-v1}: words in a report, in English or
 * Portuguese, that may mean danger to people. A match raises an alert for an
 * administrator to review. It is not a diagnosis, it sets no urgency, and a
 * report that matches nothing is not thereby safe (US-14: the rules do not
 * guarantee that every emergency is detected).
 *
 * <p>How a report is read:
 *
 * <ul>
 *   <li><b>Words.</b> Lower case, without accents or apostrophes, and with
 *       doubled letters read as one ({@code gass} is {@code gas}). Other
 *       misspellings are not recognised, except the variants listed in a
 *       rule.</li>
 *   <li><b>Patterns.</b> A rule has several patterns. A pattern is one word,
 *       or two or three words in any order within a few words of each other
 *       ({@code smell} and {@code gas}). {@code *} ends a word that may go
 *       on ({@code smell*} is also {@code smells} and {@code smelly}).</li>
 *   <li><b>Negation.</b> A match is ignored only when a negation stands
 *       before its first word in the same clause, with at most two small words
 *       and any words of the same pattern between ({@code no smell of gas},
 *       {@code no gas leak}, {@code não há fumo}). Portuguese {@code no} ("in
 *       the") before a Portuguese word is not a negation ({@code no elevador}).
 *       Any other wording raises the alert, also when the same words were
 *       denied earlier ({@code no gas leak, but now I smell gas}): the rules
 *       prefer a false alarm to a missed one.</li>
 * </ul>
 *
 * <p>The expected cases, including negation and misspellings, are in
 * {@code CriticalAlertsV1Test}. A change to a rule is a new version of the
 * rule set, because every stored signal names the version that raised it.
 */
public final class CriticalAlertsV1 implements CriticalRules {

    public static final String VERSION = "critical-alerts-v1";

    public static final String SMOKE_OR_FIRE = "smoke_or_fire";
    public static final String GAS_SMELL = "gas_smell";
    public static final String EXPOSED_WIRING = "exposed_wiring";
    public static final String WATER_NEAR_ELECTRICS = "water_near_electrics";
    public static final String ELECTRIC_SHOCK = "electric_shock";
    public static final String PERSON_TRAPPED = "person_trapped";
    public static final String COLLAPSE_RISK = "collapse_risk";

    /** The words of a pattern lie within this many consecutive words. */
    private static final int NEAR = 8;
    /** A person, a lift and "stuck" are often further apart. */
    private static final int WIDE = 10;
    /** Small words allowed between a negation and what it negates. */
    private static final int MAX_FILLERS = 2;

    private static final Set<String> NEGATIONS = words(
            "no not without never isnt arent wasnt werent dont doesnt didnt cant cannot couldnt wont"
                    + " nao sem nem nenhum nenhuma nunca jamais");
    /** Portuguese {@code no} is also "in the" (em + o): before a Portuguese word it is not a negation. */
    private static final String NO = "no";
    private static final Set<String> FILLERS = words(
            "a an any the of sign signs smell is are there on ha de do da um uma o qualquer sinal sinais cheiro esta em");

    private static final List<Rule> RULES = List.of(
            new Rule(SMOKE_OR_FIRE,
                    one("smoke|smokes|smoking|smoky|smok / fumo|fumos|fumaca|fumarada|fumega|fumegar"),
                    one("fire|fires").unlessFollowedBy(
                            "extinguisher extinguishers door doors exit exits escape escapes hose hoses drill drills"),
                    one("flame|flames|blaze|ablaze / chamas|arder|arde|ardeu|ardem"),
                    one(" / fogo|fogos").unlessPrecededBy("corta"),
                    one(" / incendio|incendios|insendio|incendiou|incendiar"),
                    all(NEAR, "burn* / queim*", "smell*|odor|odour / cheir*"),
                    one("spark|sparks|sparking|sparked / faisca|faiscas|faiscar")),
            new Rule(GAS_SMELL,
                    all(NEAR, "gas|gaz", "smell*|odor|odour|stink*|stench|leak*|escap* / cheir*|fuga|fugas"),
                    one("monoxide / monoxido")),
            new Rule(EXPOSED_WIRING,
                    all(NEAR, "wire|wires|wiring|cable|cables / fio|fios|cabo|cabos|cablagem",
                            "exposed|bare|loose|hanging|live|frayed / descarnad*|desencapad*|expost*|descobert*"
                                    + "|pendurad*|solto|soltos|solta|soltas")),
            new Rule(WATER_NEAR_ELECTRICS,
                    all(NEAR, "water|flood*|wet|drip*|infiltr* / agua|aguas|inund*|molhad*|ping*",
                            "socket*|outlet*|electri*|fuse*|switch|switches|switchboard|plug|plugs"
                                    + " / eletri*|tomada*|disjuntor*|interruptor*|fusive*")),
            new Rule(ELECTRIC_SHOCK,
                    one("electrocut* / eletrocut*|electrocuc*|eletrocuc*"),
                    all(NEAR, "shock|shocks|shocked / choque|choques",
                            "electri*|socket*|outlet*|switch*|wire|wires|touch*"
                                    + " / eletri*|tomada*|interruptor*|fio|fios|toc*|apanh*|levei|levou")),
            new Rule(PERSON_TRAPPED,
                    all(WIDE, "trapped|stuck|locked / preso|presa|presos|presas|fechado|fechada|fechados|fechadas"
                                    + "|encurralad*|retid*",
                            "lift|lifts|elevator* / elevador*|ascensor*",
                            "inside|person|people|someone|somebody|child*|kid|kids|neighbo*|resident*|woman|man|lady"
                                    + "|mother|father|mum|mom|dad|wife|husband|son|daughter|baby|elderly|am|im|were"
                                    + " / pessoa*|alguem|crianca*|vizinh*|morador*|dentro|senhor*|idos*|mae|pai|filh*"
                                    + "|marido|mulher|esposa|bebe|estou|estamos|fiquei|ficamos")),
            new Rule(COLLAPSE_RISK,
                    one("collaps* / desab*|derroc*|desmoron*|ruiu|ruir"),
                    all(NEAR, "ceiling|roof|wall|balcony|facade / teto|tecto|telhado|parede|varanda|fachada|muro",
                            "fell|fallen|falling / caiu|cair|cairam|caindo|queda")));

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<String> evaluate(ReportText text) {
        Words words = Words.of(text.value());
        return RULES.stream().filter(rule -> rule.matches(words)).map(Rule::code).toList();
    }

    private record Rule(String code, Pattern... patterns) {

        boolean matches(Words words) {
            return Arrays.stream(patterns).anyMatch(pattern -> pattern.matches(words));
        }
    }

    private static Pattern one(String alternatives) {
        return new Pattern(1, List.of(new Slot(alternatives)), Set.of(), Set.of());
    }

    private static Pattern all(int within, String... slots) {
        return new Pattern(within, Arrays.stream(slots).map(Slot::new).toList(), Set.of(), Set.of());
    }

    private static Set<String> words(String spaceSeparated) {
        return Arrays.stream(spaceSeparated.split(" ")).map(Words::squeeze).collect(Collectors.toSet());
    }

    /**
     * Every slot matched by a different word, all within {@code within}
     * consecutive words, and no negation before the first of them.
     *
     * @param notFollowedBy for a one-word pattern: the match does not count when one of these words comes
     *                      next ({@code fire extinguisher} is equipment, not a fire)
     * @param notPrecededBy the same for the word before
     */
    private record Pattern(int within, List<Slot> slots, Set<String> notFollowedBy, Set<String> notPrecededBy) {

        Pattern unlessFollowedBy(String spaceSeparated) {
            return new Pattern(within, slots, words(spaceSeparated), notPrecededBy);
        }

        Pattern unlessPrecededBy(String spaceSeparated) {
            return new Pattern(within, slots, notFollowedBy, words(spaceSeparated));
        }

        boolean matches(Words words) {
            List<int[]> candidates = new ArrayList<>();
            collect(words, 0, new int[slots.size()], candidates);
            return candidates.stream().anyMatch(candidate -> !negatedAt(words, first(candidate)));
        }

        /**
         * A negation before the word, in its clause. Between them may stand up to {@link CriticalAlertsV1#MAX_FILLERS} small words
         * and any words of this pattern, so in "no gas leak" the leak is denied too, and in "não cheira a gás, o
         * cheiro vem do lixo" so is the second smell (it follows a clause break, so its match starts at the gas).
         * A word is never spent by a denied match: "no gas leak, but now I smell gas" raises the alert.
         */
        private boolean negatedAt(Words words, int position) {
            int fillers = 0;
            for (int i = position; i > 0 && !words.startsClause(i); i--) {
                String before = words.at(i - 1);
                if (NEGATIONS.contains(before)) {
                    return !(before.equals(NO) && portugueseOnly(words.at(i)));
                }
                if (inPattern(before)) {
                    continue;
                }
                if (!FILLERS.contains(before) || ++fillers > MAX_FILLERS) {
                    return false;
                }
            }
            return false;
        }

        private boolean inPattern(String word) {
            return slots.stream().anyMatch(slot -> slot.matches(word));
        }

        private boolean portugueseOnly(String word) {
            return slots.stream().anyMatch(slot -> slot.portugueseOnly(word));
        }

        /**
         * Every way to give each slot a different word, all within {@code within} consecutive words. Only
         * positions near the first chosen word are tried, which keeps a long report cheap.
         */
        private void collect(Words words, int slot, int[] chosen, List<int[]> found) {
            if (slot == slots.size()) {
                if (acceptsPlacement(words, chosen)) {
                    found.add(chosen.clone());
                }
                return;
            }
            int from = slot == 0 ? 0 : Math.max(0, chosen[0] - within + 1);
            int to = slot == 0 ? words.size() : Math.min(words.size(), chosen[0] + within);
            for (int position = from; position < to; position++) {
                if (slots.get(slot).matches(words.at(position)) && !taken(chosen, slot, position)) {
                    chosen[slot] = position;
                    collect(words, slot + 1, chosen, found);
                }
            }
        }

        private static boolean taken(int[] chosen, int upTo, int position) {
            for (int i = 0; i < upTo; i++) {
                if (chosen[i] == position) {
                    return true;
                }
            }
            return false;
        }

        private static int first(int[] chosen) {
            return Arrays.stream(chosen).min().orElseThrow();
        }

        private boolean acceptsPlacement(Words words, int[] chosen) {
            int first = first(chosen);
            int last = Arrays.stream(chosen).max().orElseThrow();
            return last - first < within
                    && !words.follows(last, notFollowedBy)
                    && !words.precedes(first, notPrecededBy);
        }
    }

    /**
     * Alternatives separated by {@code |}; one ending in {@code *} matches any word that starts with it. Those
     * after {@code /} are Portuguese only; those before it are English, or both languages ({@code gas}).
     */
    private static final class Slot {

        private final Alternatives shared;
        private final Alternatives portuguese;

        Slot(String alternatives) {
            int split = alternatives.indexOf('/');
            shared = new Alternatives(split < 0 ? alternatives : alternatives.substring(0, split));
            portuguese = new Alternatives(split < 0 ? "" : alternatives.substring(split + 1));
        }

        boolean matches(String word) {
            return shared.matches(word) || portuguese.matches(word);
        }

        boolean portugueseOnly(String word) {
            return portuguese.matches(word) && !shared.matches(word);
        }
    }

    private static final class Alternatives {

        private final List<String> exact = new ArrayList<>();
        private final List<String> prefixes = new ArrayList<>();

        Alternatives(String alternatives) {
            for (String alternative : alternatives.split("\\|")) {
                String trimmed = alternative.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.endsWith("*")) {
                    prefixes.add(Words.squeeze(trimmed.substring(0, trimmed.length() - 1)));
                } else {
                    exact.add(Words.squeeze(trimmed));
                }
            }
        }

        boolean matches(String word) {
            return exact.contains(word) || prefixes.stream().anyMatch(word::startsWith);
        }
    }

    /** The words of a report, and where its clauses end. */
    private static final class Words {

        private final List<String> words = new ArrayList<>();
        /** For each word, whether punctuation or a line break separates it from the word before. */
        private final List<Boolean> startsClause = new ArrayList<>();

        static Words of(String text) {
            String folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("['’`]", "");
            Words result = new Words();
            StringBuilder word = new StringBuilder();
            boolean clauseBreak = true;
            for (int i = 0; i <= folded.length(); i++) {
                char c = i < folded.length() ? folded.charAt(i) : ' ';
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    word.append(c);
                    continue;
                }
                if (!word.isEmpty()) {
                    result.words.add(squeeze(word.toString()));
                    result.startsClause.add(clauseBreak);
                    word.setLength(0);
                    clauseBreak = false;
                }
                if (c != ' ' && c != '\t' && c != '-') {
                    clauseBreak = true;
                }
            }
            return result;
        }

        /** Doubled letters read as one, so {@code smelll} and {@code gass} are still recognised. */
        static String squeeze(String word) {
            return word.replaceAll("(.)\\1+", "$1");
        }

        int size() {
            return words.size();
        }

        String at(int position) {
            return words.get(position);
        }

        /** Whether the word right after {@code position}, in the same clause, is one of {@code these}. */
        boolean follows(int position, Set<String> these) {
            int next = position + 1;
            return !these.isEmpty() && next < words.size() && !startsClause.get(next) && these.contains(words.get(next));
        }

        /** Whether the word right before {@code position}, in the same clause, is one of {@code these}. */
        boolean precedes(int position, Set<String> these) {
            return !these.isEmpty() && position > 0 && !startsClause.get(position)
                    && these.contains(words.get(position - 1));
        }

        /** Whether punctuation or a line break separates this word from the one before. */
        boolean startsClause(int position) {
            return startsClause.get(position);
        }
    }
}
