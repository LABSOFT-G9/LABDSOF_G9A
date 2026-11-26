package pt.isep.bmc.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * The expected cases of {@code critical-alerts-v1} (US-14: critical and
 * non-critical text, negation, spelling, ambiguous wording), in English and
 * Portuguese. These are the cases the rule set was written against. They are
 * not the evaluation set of US-15, which is kept apart.
 */
class CriticalAlertsV1Test {

    final CriticalAlertsV1 rules = new CriticalAlertsV1();

    List<String> codes(String text) {
        return rules.evaluate(ReportText.of(text));
    }

    @Test
    void namesItsVersion() {
        assertThat(rules.version()).isEqualTo("critical-alerts-v1");
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @CsvSource(delimiter = '|', textBlock = """
            There is smoke coming out of the socket in the hall                 | smoke_or_fire
            The bin room is on fire!                                            | smoke_or_fire
            Strong burning smell in the stairwell since this morning            | smoke_or_fire
            Sparks come out of the light switch when I touch it                 | smoke_or_fire
            Há fumo a sair do quadro elétrico da garagem                        | smoke_or_fire
            Está um contentor a arder junto à entrada                           | smoke_or_fire
            Cheira a queimado no elevador desde ontem                           | smoke_or_fire
            Houve um incêndio na casa do lixo                                   | smoke_or_fire
            There is a strong smell of gas on the second floor                  | gas_smell
            I think there is a gas leak in the kitchen of unit 2B               | gas_smell
            There is a strange smell and someone mentioned gas                  | gas_smell
            The carbon monoxide alarm keeps going off                           | gas_smell
            Cheira muito a gás nas escadas do prédio                            | gas_smell
            Há uma fuga de gás na cozinha                                       | gas_smell
            There are exposed wires hanging from the garage ceiling             | exposed_wiring
            A live cable is loose next to the lift door                         | exposed_wiring
            Há fios descarnados no corredor do terceiro piso                    | exposed_wiring
            Um cabo solto está pendurado na garagem                             | exposed_wiring
            Water is dripping onto the fuse box in the garage                   | water_near_electrics
            The socket in the laundry room is wet after the flood               | water_near_electrics
            Está a pingar água para cima do quadro elétrico                     | water_near_electrics
            A tomada da lavandaria está molhada                                 | water_near_electrics
            I got an electric shock from the light switch in the hall           | electric_shock
            My son was almost electrocuted by the gate motor                    | electric_shock
            Apanhei um choque ao tocar no corrimão                              | electric_shock
            Someone is trapped in the lift between floors 2 and 3               | person_trapped
            I am stuck in the elevator, please help                             | person_trapped
            Está uma pessoa presa no elevador do bloco A                        | person_trapped
            Estou fechado dentro do ascensor                                    | person_trapped
            Part of the garage ceiling has collapsed                            | collapse_risk
            A piece of the balcony wall fell onto the pavement                  | collapse_risk
            O teto da garagem desabou durante a noite                           | collapse_risk
            Caiu um bocado da fachada para o passeio                            | collapse_risk
            """)
    void raisesTheRule(String text, String code) {
        assertThat(codes(text)).contains(code);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            The lift is stuck on the third floor again
            The stair light on floor 2 has been out for a week
            Water dripping from the ceiling light in the garage, level -1.
            The gas meter reading for unit 2B looks wrong
            The fire extinguisher on floor 2 is past its date
            The fire door to the garage does not close
            The garage door makes a loud noise when it opens
            A porta corta-fogo da garagem não fecha
            O elevador está parado no rés-do-chão
            A lâmpada das escadas está fundida há uma semana
            The bulb in the hallway burnt out last night
            There is no hot water in the building since Monday
            The cleaning of the stairs was not done this week
            The intercom of unit 3A does not work
            """)
    void raisesNothingForAnOrdinaryFault(String text) {
        assertThat(codes(text)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            There is no smoke, only a broken lamp in the hall
            I checked and there is no smell of gas in the kitchen
            It is not a gas leak, the smell comes from the bins
            There is no sign of fire, the alarm rang for nothing
            No exposed wires, just a missing cover on the box
            Não há fumo, é só uma lâmpada fundida
            Não cheira a gás, o cheiro vem do lixo
            Sem sinais de fogo no contentor, foi falso alarme
            """)
    void aNegationRightBeforeTheWordsRaisesNothing(String text) {
        assertThat(codes(text)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @CsvSource(delimiter = '|', textBlock = """
            No. There is smoke everywhere on the landing                        | smoke_or_fire
            The smoke has not stopped since noon                                | smoke_or_fire
            I do not know if it is gas but there is a strong smell              | gas_smell
            Nobody answers and there is no light, but I can smell gas           | gas_smell
            There is no water but the socket by the wet floor gives off smoke   | smoke_or_fire
            It is not a water leak, it is a gas leak                            | gas_smell
            Não sei se é gás mas cheira muito mal                               | gas_smell
            Não é brincadeira: há fogo na garagem                               | smoke_or_fire
            """)
    void anyOtherWordingWithANegationStillRaisesTheAlert(String text, String code) {
        assertThat(codes(text)).contains(code);
    }

    @ParameterizedTest(name = "[{index}] {1}: {0}")
    @CsvSource(delimiter = '|', textBlock = """
            THERE IS SMOKE IN THE GARAGE                                        | smoke_or_fire
            there is a smelll of gass in the hall                               | gas_smell
            Ha fumo na garagem e cheira a gaz                                   | gas_smell
            HÁ FUMO NA GARAGEM                                                  | smoke_or_fire
            Exposed wirring behind the mailboxes                                | exposed_wiring
            Insendio no contentor do lixo                                       | smoke_or_fire
            Smoke, lots of smoke!!! Come quickly...                             | smoke_or_fire
            There's a gas-smell in the corridor                                 | gas_smell
            """)
    void readsCapitalsAccentsDoubledLettersAndListedMisspellings(String text, String code) {
        assertThat(codes(text)).contains(code);
    }

    @Test
    void aMisspellingThatIsNotListedIsMissed() {
        // A known limit, stated in the rule set: only doubled letters and listed variants are recognised.
        assertThat(codes("There is smeok in the garage and it smells odd")).isEmpty();
    }

    @Test
    void raisesEveryRuleThatMatchesEachOnceInTheRuleSetsOrder() {
        assertThat(codes("Smoke and more smoke from the fuse box, water is dripping on it and a wire is exposed. "
                + "I got an electric shock."))
                .containsExactly("smoke_or_fire", "exposed_wiring", "water_near_electrics", "electric_shock");
    }

    @Test
    void wordsOfOnePatternMustBeCloseToEachOther() {
        assertThat(codes("The gas bill arrived late this month and, on another matter entirely, the bins "
                + "by the back door have a bad smell")).isEmpty();
    }

    @Test
    void aLongReportIsReadQuickly() {
        String text = "I am stuck in the lift. ".repeat(160);
        long start = System.nanoTime();
        assertThat(codes(text)).containsExactly("person_trapped");
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }
}
