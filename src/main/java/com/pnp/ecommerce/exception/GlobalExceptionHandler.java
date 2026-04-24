package com.pnp.ecommerce.exception;

import com.pnp.ecommerce.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Single source of truth for HTTP error responses. Every non-2xx
 * response leaves the system through a method on this class, so the
 * envelope shape ({@link ErrorResponse}) stays uniform across the API.
 *
 * <p>Why a central advice (vs. controller-local {@code @ExceptionHandler}
 * methods): the same exception needs identical treatment wherever it's
 * thrown. Duplicating mappings per controller is a recipe for drift —
 * one controller returns 409 for {@code InsufficientStockException},
 * another forgets and returns 500. A single advice prevents that class
 * of bug entirely.
 *
 * <p>Why one handler method per {@link DomainException} subtype (vs.
 * a single handler on the sealed base with {@code instanceof} branches):
 * Spring's dispatcher picks the most specific {@code @ExceptionHandler},
 * so declarative mapping is both faster and clearer than a manual
 * switch. The sealing still provides compile-time assurance that any
 * new subtype will be an unmapped-handler hole that code review catches.
 *
 * <p>Uses the injected {@link Clock} rather than {@code Instant.now()}
 * so tests can freeze time and assert on the {@code timestamp} field.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String MESSAGE_INTERNAL_ERROR = "an internal error occurred";

    private final Clock clock;

    public GlobalExceptionHandler(final Clock clock) {
        this.clock = clock;
    }

    // ---- Domain exceptions (sealed hierarchy) -------------------------------
    //
    // Grouped by HTTP status so adding a new domain exception only requires
    // appending it to the matching @ExceptionHandler value list. The sealed
    // hierarchy still guarantees the full set is reviewable in one place
    // ({@link DomainException}).

    @ExceptionHandler({ProductNotFoundException.class, OrderNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleDomainNotFound(final DomainException ex,
                                                              final HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), req, List.of());
    }

    @ExceptionHandler({InsufficientStockException.class, IllegalOrderStateException.class})
    public ResponseEntity<ErrorResponse> handleDomainConflict(final DomainException ex,
                                                              final HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(InvalidReportRangeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidReportRange(final InvalidReportRangeException ex,
                                                                  final HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req, List.of());
    }

    // ---- Validation errors --------------------------------------------------

    /**
     * Fired when {@code @Valid @RequestBody} validation fails on a DTO
     * (e.g. blank name, negative price, class-level
     * {@code @UniqueProductIds}).
     *
     * <p>Field-level and class-level violations are merged into a
     * single {@code fieldErrors} list so clients have one place to
     * look. Class-level errors carry the offending field name
     * (e.g. {@code items}) via {@link org.springframework.validation.ObjectError#getObjectName()}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(final MethodArgumentNotValidException ex,
                                                              final HttpServletRequest req) {
        final List<ErrorResponse.FieldError> fieldErrors = Stream.concat(
                        ex.getBindingResult().getFieldErrors().stream()
                                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage())),
                        ex.getBindingResult().getGlobalErrors().stream()
                                .map(oe -> new ErrorResponse.FieldError(oe.getObjectName(), oe.getDefaultMessage())))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
                "request validation failed", req, fieldErrors);
    }

    /**
     * Fired for validation on path / query parameters (where
     * {@link MethodArgumentNotValidException} does not apply). Maps the
     * same way as body validation for a consistent client experience.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(final ConstraintViolationException ex,
                                                                   final HttpServletRequest req) {
        final List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
                "request validation failed", req, fieldErrors);
    }

    // ---- Malformed requests -------------------------------------------------

    /** Unparseable JSON body (e.g. trailing comma, wrong type on a field). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(final HttpMessageNotReadableException ex,
                                                          final HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.MALFORMED_REQUEST,
                "request body is malformed or missing", req, List.of());
    }

    /** Path variable type mismatch, e.g. {@code /orders/abc} on a long id. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(final MethodArgumentTypeMismatchException ex,
                                                            final HttpServletRequest req) {
        final String message = "parameter '" + ex.getName() + "' has an invalid value";
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.MALFORMED_REQUEST, message, req, List.of());
    }

    // ---- Routing errors -----------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(final HttpRequestMethodNotSupportedException ex,
                                                                  final HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED,
                ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(final NoResourceFoundException ex,
                                                          final HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND,
                "no handler found for " + req.getMethod() + " " + req.getRequestURI(),
                req, List.of());
    }

    // ---- Catch-all ----------------------------------------------------------

    /**
     * Final safety net. We deliberately do NOT leak {@code ex.getMessage()}
     * here because upstream libraries can include internal paths,
     * query fragments, or class names that should never reach a client.
     * The full stack trace is logged at ERROR for operators.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(final Exception ex, final HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                MESSAGE_INTERNAL_ERROR, req, List.of());
    }

    // ---- Helpers ------------------------------------------------------------

    private ResponseEntity<ErrorResponse> build(final HttpStatus status,
                                                final String code,
                                                final String message,
                                                final HttpServletRequest req,
                                                final List<ErrorResponse.FieldError> fieldErrors) {
        final ErrorResponse body = new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                req.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }

    private static ErrorResponse.FieldError toFieldError(final ConstraintViolation<?> violation) {
        final String path = violation.getPropertyPath() == null
                ? ""
                : violation.getPropertyPath().toString();
        return new ErrorResponse.FieldError(path, violation.getMessage());
    }
}
