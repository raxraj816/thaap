package com.thaap.payments.webhook;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SocketWebhookHttpTransport implements WebhookHttpTransport {

    static final int MAX_LINE_BYTES = 8_192;
    private static final int MAX_INFORMATIONAL_RESPONSES = 8;
    static final int MAX_RESPONSE_HEAD_BYTES = 32_768;

    private final int connectTimeoutMillis;
    private final int requestTimeoutMillis;

    public SocketWebhookHttpTransport(
            @Value("${thaap.webhook.connect-timeout:2s}") Duration connectTimeout,
            @Value("${thaap.webhook.request-timeout:5s}") Duration requestTimeout) {
        this.connectTimeoutMillis = checkedMillis(connectTimeout, "connect timeout");
        this.requestTimeoutMillis = checkedMillis(requestTimeout, "request timeout");
    }

    @Override
    public WebhookHttpResponse send(WebhookHttpRequest request) {
        byte[] payload = request.payload().getBytes(StandardCharsets.UTF_8);
        if (payload.length > WebhookEventEnvelopeFactory.MAX_PAYLOAD_BYTES) {
            throw new WebhookTransportException(
                    "Webhook payload exceeded the transport byte limit",
                    WebhookDeliveryErrorCode.PAYLOAD_INTEGRITY_FAILED,
                    false,
                    new IllegalArgumentException("Webhook payload is larger than 64 KiB"));
        }
        boolean connected = false;
        Boolean[] requestSent = {false};
        AtomicReference<Socket> activeSocket = new AtomicReference<>();
        AtomicBoolean requestDeadlineExceeded = new AtomicBoolean();
        Thread requestDeadline = null;
        try (Socket plainSocket = new Socket()) {
            activeSocket.set(plainSocket);
            plainSocket.connect(new InetSocketAddress(request.target().address(), request.target().port()),
                    connectTimeoutMillis);
            connected = true;
            requestDeadline = startRequestDeadline(activeSocket, requestDeadlineExceeded);
            plainSocket.setSoTimeout(requestTimeoutMillis);
            if (request.target().uri().getScheme().equalsIgnoreCase("https")) {
                try (SSLSocket tlsSocket = tlsSocket(plainSocket, request.target())) {
                    activeSocket.set(tlsSocket);
                    tlsSocket.setSoTimeout(requestTimeoutMillis);
                    tlsSocket.startHandshake();
                    return exchange(tlsSocket, request, payload, requestSent);
                }
            }
            return exchange(plainSocket, request, payload, requestSent);
        } catch (SocketTimeoutException exception) {
            WebhookDeliveryErrorCode code = connected
                    ? WebhookDeliveryErrorCode.REQUEST_TIMEOUT
                    : WebhookDeliveryErrorCode.CONNECT_TIMEOUT;
            throw new WebhookTransportException("Webhook HTTP operation timed out", code,
                    requestSent[0], exception);
        } catch (IOException exception) {
            if (requestDeadlineExceeded.get()) {
                throw new WebhookTransportException("Webhook HTTP request deadline was exceeded",
                        WebhookDeliveryErrorCode.REQUEST_TIMEOUT, requestSent[0], exception);
            }
            throw new WebhookTransportException("Webhook HTTP transport failed",
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR, requestSent[0], exception);
        } finally {
            activeSocket.set(null);
            if (requestDeadline != null) {
                requestDeadline.interrupt();
            }
        }
    }

    private Thread startRequestDeadline(AtomicReference<Socket> activeSocket,
            AtomicBoolean requestDeadlineExceeded) {
        return Thread.ofVirtual().name("thaap-webhook-request-deadline").start(() -> {
            try {
                Thread.sleep(requestTimeoutMillis);
                Socket socket = activeSocket.get();
                if (socket != null) {
                    requestDeadlineExceeded.set(true);
                    socket.close();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Closing the socket is best-effort; the delivery path still observes its I/O result.
            }
        });
    }

    private SSLSocket tlsSocket(Socket plainSocket, ValidatedWebhookTarget target) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) factory.createSocket(
                plainSocket, target.uri().getHost(), target.port(), true);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        return socket;
    }

    private WebhookHttpResponse exchange(Socket socket, WebhookHttpRequest request,
            byte[] payload, Boolean[] requestSent) throws IOException {
        URI uri = request.target().uri();
        String headers = "POST " + requestTarget(uri) + " HTTP/1.1\r\n"
                + "Host: " + hostHeader(request.target()) + "\r\n"
                + "Content-Type: application/json\r\n"
                + "User-Agent: THAAP-Webhook/1.0\r\n"
                + "THAAP-Event-Id: " + request.eventId() + "\r\n"
                + "THAAP-Delivery-Id: " + request.deliveryId() + "\r\n"
                + "THAAP-Signature: " + request.signatureHeader() + "\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
        requestSent[0] = null;
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
        requestSent[0] = true;

        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        return new WebhookHttpResponse(readFinalStatusCode(input));
    }

    private String requestTarget(URI uri) {
        URI asciiUri = URI.create(uri.toASCIIString());
        String path = asciiUri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (asciiUri.getRawQuery() != null) {
            path += "?" + asciiUri.getRawQuery();
        }
        return path;
    }

    private int readFinalStatusCode(BufferedInputStream input) throws IOException {
        ResponseHeadBudget budget = new ResponseHeadBudget(MAX_RESPONSE_HEAD_BYTES);
        for (int informationalResponses = 0;
                informationalResponses <= MAX_INFORMATIONAL_RESPONSES;
                informationalResponses++) {
            String statusLine = readLine(input, budget);
            int statusCode = parseStatusCode(statusLine);
            discardResponseHeaders(input, budget);
            if (statusCode >= 200) {
                return statusCode;
            }
            if (informationalResponses == MAX_INFORMATIONAL_RESPONSES) {
                throw new IOException("Webhook target returned too many informational responses");
            }
        }
        throw new IOException("Webhook target did not return a final HTTP response");
    }

    private int parseStatusCode(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length != 3
                || (!parts[0].equals("HTTP/1.0") && !parts[0].equals("HTTP/1.1"))
                || parts[1].length() != 3
                || !parts[1].chars().allMatch(character -> character >= '0' && character <= '9')
                || !isValidReasonPhrase(parts[2])) {
            throw new IOException("Webhook target returned an invalid HTTP status line");
        }
        try {
            int statusCode = Integer.parseInt(parts[1]);
            if (statusCode < 100 || statusCode > 599) {
                throw new IOException("Webhook target returned an out-of-range HTTP status code");
            }
            return statusCode;
        } catch (NumberFormatException exception) {
            throw new IOException("Webhook target returned an invalid HTTP status code", exception);
        }
    }

    private boolean isValidReasonPhrase(String reasonPhrase) {
        return reasonPhrase.chars().allMatch(
                SocketWebhookHttpTransport::isValidResponseTextCharacter);
    }

    private void discardResponseHeaders(BufferedInputStream input, ResponseHeadBudget budget) throws IOException {
        while (true) {
            String headerLine = readLine(input, budget);
            if (headerLine.isEmpty()) {
                return;
            }
            if (!isValidResponseHeaderLine(headerLine)) {
                throw new IOException("Webhook target returned an invalid HTTP header field");
            }
        }
    }

    static boolean isValidResponseHeaderLine(String headerLine) {
        int separator = headerLine.indexOf(':');
        if (separator < 1) {
            return false;
        }
        for (int index = 0; index < separator; index++) {
            if (!isHeaderNameCharacter(headerLine.charAt(index))) {
                return false;
            }
        }
        for (int index = separator + 1; index < headerLine.length(); index++) {
            int character = headerLine.charAt(index);
            if (!isValidResponseTextCharacter(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidResponseTextCharacter(int character) {
        return character == '\t' || (character >= 0x20 && character <= 0x7E);
    }

    private static boolean isHeaderNameCharacter(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || switch (character) {
                    case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~' -> true;
                    default -> false;
                };
    }

    private String readLine(BufferedInputStream input, ResponseHeadBudget budget) throws IOException {
        byte[] bytes = new byte[MAX_LINE_BYTES];
        int length = 0;
        int previous = -1;
        while (length < bytes.length) {
            int current = input.read();
            if (current < 0) {
                break;
            }
            budget.consumeByte();
            if (previous == '\r' && current == '\n') {
                return new String(bytes, 0, length - 1, StandardCharsets.US_ASCII);
            }
            bytes[length++] = (byte) current;
            previous = current;
        }
        throw new IOException("Webhook target response line was missing or too large");
    }

    private static final class ResponseHeadBudget {

        private int remainingBytes;

        private ResponseHeadBudget(int remainingBytes) {
            this.remainingBytes = remainingBytes;
        }

        private void consumeByte() throws IOException {
            remainingBytes--;
            if (remainingBytes < 0) {
                throw new IOException("Webhook target response head was too large");
            }
        }
    }

    String hostHeader(ValidatedWebhookTarget target) {
        String host = target.uri().getHost();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        boolean defaultPort = target.uri().getScheme().equalsIgnoreCase("https")
                ? target.port() == 443
                : target.port() == 80;
        return defaultPort ? host : host + ":" + target.port();
    }

    static int checkedMillis(Duration duration, String label) {
        return WebhookDurationValidator.checkedWholeMilliseconds(
                duration, label, Integer.MAX_VALUE);
    }
}
