package pt.isep.bmc.calendarsim.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

/**
 * Every invalid request answers {@code 422 INVALID_REQUEST} with the fields
 * at fault, as openapi.yaml says, also when the body cannot be read at all
 * (bad JSON, or a field of the wrong type). Spring's own {@code 400} would
 * carry no {@code code}, and Dispatch maps only the documented answers.
 */
@RestControllerAdvice
class ProblemHandler {

    @ExceptionHandler(BookingRequests.InvalidRequestException.class)
    ResponseEntity<Object> invalid(BookingRequests.InvalidRequestException e) {
        return Problems.invalid(e.errors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> unreadable(HttpMessageNotReadableException e) {
        return Problems.invalid(List.of(field(e)));
    }

    /** The field whose value has the wrong type, such as {@code window} or {@code outcomes[0].delayMs}; else the body. */
    private static Map<String, String> field(HttpMessageNotReadableException e) {
        if (e.getMostSpecificCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            StringBuilder path = new StringBuilder();
            for (JacksonException.Reference reference : jackson.getPath()) {
                if (reference.getPropertyName() != null) {
                    path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
                } else if (reference.getIndex() >= 0) {
                    path.append('[').append(reference.getIndex()).append(']');
                }
            }
            if (!path.isEmpty()) {
                return Map.of("field", path.toString(), "reason", "invalid_type");
            }
        }
        return Map.of("field", "body", "reason", "unreadable");
    }
}
