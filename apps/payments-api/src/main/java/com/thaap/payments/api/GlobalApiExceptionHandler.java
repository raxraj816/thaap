package com.thaap.payments.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import com.thaap.payments.ratelimit.RateLimitExceededException;
import com.thaap.payments.ratelimit.RateLimitUnavailableException;
import com.thaap.payments.workspace.SavedSearchUpgradeRequiredException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleRequestBodyValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request, HttpServletResponse response) {
        List<ApiErrorField> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> new ApiErrorField(error.getField(), defaultMessage(error.getDefaultMessage())))
                .toList();
        List<ApiErrorField> globalErrors = exception.getBindingResult().getGlobalErrors().stream()
                .map(error -> new ApiErrorField(error.getObjectName(), defaultMessage(error.getDefaultMessage())))
                .toList();
        List<ApiErrorField> errors = new ArrayList<>();
        errors.addAll(fieldErrors);
        errors.addAll(globalErrors);
        return error(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed", request, response,
                errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(HandlerMethodValidationException exception,
            HttpServletRequest request, HttpServletResponse response) {
        List<ApiErrorField> errors = exception.getParameterValidationResults().stream()
                .flatMap((ParameterValidationResult result) -> result.getResolvableErrors().stream()
                        .map(error -> new ApiErrorField(parameterName(result.getMethodParameter()), message(error))))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "validation_failed", "Request parameter validation failed", request, response,
                errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception,
            HttpServletRequest request, HttpServletResponse response) {
        List<ApiErrorField> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiErrorField(leafProperty(violation.getPropertyPath().toString()),
                        defaultMessage(violation.getMessage())))
                .sorted(Comparator.comparing(ApiErrorField::field))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "validation_failed", "Request parameter validation failed", request, response,
                errors);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request,
            HttpServletResponse response) {
        return error(HttpStatus.BAD_REQUEST, "bad_request", badRequestMessage(exception), request, response, List.of());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception,
            HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return error(status, errorCode(status), reasonOrDefault(exception.getReason(), status), request, response,
                List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception,
            HttpServletRequest request, HttpServletResponse response) {
        return error(HttpStatus.NOT_FOUND, "not_found", "Resource was not found", request, response, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request, HttpServletResponse response) {
        if (exception.getSupportedHttpMethods() != null) {
            response.setHeader(HttpHeaders.ALLOW, exception.getSupportedHttpMethods().stream()
                    .map(org.springframework.http.HttpMethod::name)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        return error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Request method is not supported",
                request, response, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request, HttpServletResponse response) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                "Request content type is not supported", request, response, List.of());
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiErrorResponse> handleErrorResponse(ErrorResponseException exception,
            HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return error(status, errorCode(status), reasonOrDefault(exception.getBody().getDetail(), status), request,
                response,
                List.of());
    }

    @ExceptionHandler(QueryTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleQueryTimeout(QueryTimeoutException exception,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "query_timeout",
                "Database query exceeded its bounded execution time", request, response, List.of());
    }

    @ExceptionHandler(SavedSearchUpgradeRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleSavedSearchUpgrade(
            SavedSearchUpgradeRequiredException exception,
            HttpServletRequest request, HttpServletResponse response) {
        return error(HttpStatus.CONFLICT, "saved_search_upgrade_required",
                exception.getMessage(), request, response, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = requestId(request, response);
        log.error("Unhandled API exception requestId={} path={} exceptionType={}",
                requestId, request.getRequestURI(), exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error", "Internal server error", request,
                response,
                List.of());
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String error, String message,
            HttpServletRequest request, HttpServletResponse response, List<ApiErrorField> fieldErrors) {
        String requestId = requestId(request, response);
        ApiErrorResponse body = new ApiErrorResponse(requestId, Instant.now(), status.value(), error, message,
                request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    private String requestId(HttpServletRequest request, HttpServletResponse response) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId != null && !requestId.toString().isBlank()) {
            return requestId.toString();
        }
        String fallback = UUID.randomUUID().toString();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, fallback);
        response.setHeader(RequestIdFilter.REQUEST_ID_HEADER, fallback);
        return fallback;
    }

    private String errorCode(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "bad_request";
            case UNAUTHORIZED -> "unauthorized";
            case NOT_FOUND -> "not_found";
            case METHOD_NOT_ALLOWED -> "method_not_allowed";
            case CONFLICT -> "conflict";
            case INTERNAL_SERVER_ERROR -> "internal_server_error";
            default -> status.is4xxClientError() ? "client_error" : "server_error";
        };
    }

    private String reasonOrDefault(String reason, HttpStatus status) {
        if (reason == null || reason.isBlank()) {
            return status.getReasonPhrase();
        }
        return reason;
    }

    private String defaultMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Invalid value";
        }
        return message;
    }

    private String message(MessageSourceResolvable resolvable) {
        return defaultMessage(resolvable.getDefaultMessage());
    }

    private String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            return annotationName(requestParam.name(), requestParam.value(), parameter);
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            return annotationName(requestHeader.name(), requestHeader.value(), parameter);
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return annotationName(pathVariable.name(), pathVariable.value(), parameter);
        }
        return fallbackParameterName(parameter);
    }

    private String annotationName(String name, String value, MethodParameter parameter) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallbackParameterName(parameter);
    }

    private String fallbackParameterName(MethodParameter parameter) {
        String parameterName = parameter.getParameterName();
        return parameterName == null || parameterName.isBlank() ? "parameter" : parameterName;
    }

    private String badRequestMessage(Exception exception) {
        if (exception instanceof MissingRequestHeaderException missingHeader) {
            return "Missing " + missingHeader.getHeaderName() + " header";
        }
        if (exception instanceof MissingServletRequestParameterException missingParameter) {
            return "Missing " + missingParameter.getParameterName() + " parameter";
        }
        if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            return "Invalid " + mismatch.getName() + " parameter";
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return "Request body is malformed or unreadable";
        }
        return "Bad request";
    }

    private String leafProperty(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        if (dot < 0 || dot == propertyPath.length() - 1) {
            return propertyPath;
        }
        return propertyPath.substring(dot + 1);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(RateLimitExceededException exception,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()));
        return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded",
                "Application-owned request rate limit exceeded", request, response, List.of());
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitUnavailable(RateLimitUnavailableException exception,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "rate_limit_unavailable",
                "Request rate-limit dependency is unavailable", request, response, List.of());
    }
}
