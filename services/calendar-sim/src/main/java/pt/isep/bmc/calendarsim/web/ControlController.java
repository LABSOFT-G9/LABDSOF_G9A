package pt.isep.bmc.calendarsim.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.calendarsim.booking.Calendar;
import pt.isep.bmc.calendarsim.booking.Operation;
import pt.isep.bmc.calendarsim.booking.Outcome;

/**
 * Scripts the simulator's failures for tests and demos (US-08): the outcomes
 * of the next calls of each operation, and lost bookings. Exists only with
 * {@code SIM_CONTROL_ENABLED=true}; otherwise every path answers {@code 404}.
 */
@RestController
@RequestMapping(path = "/control", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "sim.control-enabled", havingValue = "true")
class ControlController {

    static final Duration MAX_DELAY = Duration.ofSeconds(60);

    /** The outcomes each operation can be given; the others make no sense for it. */
    private static final Map<Operation, Set<Outcome.Type>> ALLOWED = Map.of(
            Operation.CREATE, Set.of(Outcome.Type.values()),
            Operation.QUERY, Set.of(Outcome.Type.OK, Outcome.Type.UNAVAILABLE, Outcome.Type.INVALID,
                    Outcome.Type.DELAY),
            Operation.CANCEL, Set.of(Outcome.Type.OK, Outcome.Type.UNAVAILABLE, Outcome.Type.DELAY));

    private final Calendar calendar;

    ControlController(Calendar calendar) {
        this.calendar = calendar;
    }

    /** Appends outcomes to an operation's queue; each call of it takes the next one. */
    @PostMapping(path = "/outcomes", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> script(@RequestBody(required = false) Bodies.Script body) {
        List<Map<String, String>> errors = new ArrayList<>();
        Operation operation = null;
        try {
            operation = Operation.fromValue(body == null ? null : body.operation());
        } catch (IllegalArgumentException e) {
            errors.add(Map.of("field", "operation", "reason", "invalid"));
        }
        List<Outcome> outcomes = new ArrayList<>();
        List<Bodies.ScriptedOutcome> raw = body == null || body.outcomes() == null ? List.of() : body.outcomes();
        if (raw.isEmpty()) {
            errors.add(Map.of("field", "outcomes", "reason", "required"));
        }
        for (int i = 0; i < raw.size(); i++) {
            String field = "outcomes[" + i + "]";
            Bodies.ScriptedOutcome item = raw.get(i);
            Outcome.Type type;
            try {
                type = Outcome.Type.fromValue(item == null ? null : item.type());
            } catch (IllegalArgumentException e) {
                errors.add(Map.of("field", field + ".type", "reason", "invalid"));
                continue;
            }
            if (operation != null && !ALLOWED.get(operation).contains(type)) {
                errors.add(Map.of("field", field + ".type", "reason", "not_for_operation"));
                continue;
            }
            Duration delay = Duration.ZERO;
            if (type == Outcome.Type.DELAY) {
                if (item.delayMs() == null || item.delayMs() < 0 || item.delayMs() > MAX_DELAY.toMillis()) {
                    errors.add(Map.of("field", field + ".delayMs", "reason", "invalid"));
                    continue;
                }
                delay = Duration.ofMillis(item.delayMs());
            }
            outcomes.add(new Outcome(type, delay));
        }
        if (!errors.isEmpty()) {
            return Problems.invalid(errors);
        }
        calendar.script(operation, outcomes);
        return ResponseEntity.noContent().build();
    }

    /** The outcomes still queued, per operation. */
    @GetMapping("/outcomes")
    Map<String, List<Map<String, Object>>> scripts() {
        Map<String, List<Map<String, Object>>> view = new LinkedHashMap<>();
        calendar.scripts().forEach((operation, outcomes) -> view.put(operation.value(), outcomes.stream()
                .map(o -> Map.<String, Object>of("type", o.type().value(), "delayMs", o.delay().toMillis()))
                .toList()));
        return view;
    }

    @DeleteMapping("/outcomes")
    ResponseEntity<Void> clearScripts() {
        calendar.clearScripts();
        return ResponseEntity.noContent().build();
    }

    /** Every booking held, cancelled ones included. */
    @GetMapping("/bookings")
    Bodies.BookingList bookings() {
        return new Bodies.BookingList(calendar.all().stream().map(Bodies.BookingBody::of).toList());
    }

    /** Loses one booking, as a provider might: Dispatch's reconciliation must report a discrepancy. */
    @DeleteMapping("/bookings/{externalRef}")
    ResponseEntity<Object> lose(@PathVariable String externalRef) {
        return calendar.lose(externalRef)
                ? ResponseEntity.noContent().build()
                : Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "No booking has this reference.");
    }

    /** Loses every booking and script, as a restart does. */
    @DeleteMapping("/bookings")
    ResponseEntity<Void> loseAll() {
        calendar.loseAll();
        calendar.clearScripts();
        return ResponseEntity.noContent().build();
    }
}
