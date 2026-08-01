package com.thaap.payments.projection;

import com.thaap.payments.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/data/quarantines")
public class QuarantineRecoveryController {

    private static final String OPERATOR_KEY_HEADER = "X-Platform-Operator-Key";

    private final QuarantineRecoveryService recoveryService;

    public QuarantineRecoveryController(QuarantineRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @GetMapping
    public List<QuarantineRecoveryService.QuarantineView> list(
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        requireLoopback(request);
        return recoveryService.list(operatorKey, status, limit);
    }

    @PostMapping("/{quarantineId}/retry")
    public QuarantineRecoveryService.RecoveryResult retry(
            @PathVariable UUID quarantineId,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireLoopback(request);
        return recoveryService.retry(operatorKey, idempotencyKey, quarantineId,
                requestId(request));
    }

    private void requireLoopback(HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        try {
            if (remoteAddress == null
                    || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Quarantine recovery routes require a loopback connection");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Quarantine recovery routes require a loopback connection");
        }
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }
}
