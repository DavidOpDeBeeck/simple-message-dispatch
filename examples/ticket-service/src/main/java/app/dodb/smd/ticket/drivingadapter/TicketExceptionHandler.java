package app.dodb.smd.ticket.drivingadapter;

import app.dodb.smd.ticket.domain.InvalidTicketTransitionException;
import app.dodb.smd.ticket.drivenadapter.ConcurrentTicketChangeException;
import app.dodb.smd.ticket.usecase.TicketNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TicketExceptionHandler {

    @ExceptionHandler(TicketNotFoundException.class)
    public ProblemDetail notFound(TicketNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception);
    }

    @ExceptionHandler({InvalidTicketTransitionException.class, ConcurrentTicketChangeException.class})
    public ProblemDetail conflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ProblemDetail badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, exception);
    }

    private static ProblemDetail problem(HttpStatus status, Exception exception) {
        var problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
