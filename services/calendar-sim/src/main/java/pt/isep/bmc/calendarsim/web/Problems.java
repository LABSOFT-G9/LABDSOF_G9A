package pt.isep.bmc.calendarsim.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** {@code application/problem+json} answers with a {@code code}, as in the BMC services (technical design 4.1). */
final class Problems {

    private Problems() {
    }

    static ResponseEntity<Object> of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }

    static ResponseEntity<Object> invalid(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The booking request is invalid.");
        problem.setProperty("code", "INVALID_REQUEST");
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    static ResponseEntity<Object> unavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "The calendar is unavailable.");
    }
}
