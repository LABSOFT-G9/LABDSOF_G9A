package pt.isep.bmc.calendarsim.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** {@code application/problem+json} answers with a {@code code}, as in the BMC services (technical design 4.1). */
final class Problems {

    private Problems() {
    }

    static ResponseEntity<Object> of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return answer(status, problem);
    }

    static ResponseEntity<Object> invalid(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The request is invalid.");
        problem.setProperty("code", "INVALID_REQUEST");
        problem.setProperty("errors", errors);
        return answer(HttpStatus.UNPROCESSABLE_CONTENT, problem);
    }

    static ResponseEntity<Object> unavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "The calendar is unavailable.");
    }

    /** The content type is set here, so a mapping that produces {@code application/json} does not replace it. */
    private static ResponseEntity<Object> answer(HttpStatus status, ProblemDetail problem) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
