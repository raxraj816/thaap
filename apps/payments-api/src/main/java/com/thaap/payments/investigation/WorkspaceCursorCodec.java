package com.thaap.payments.investigation;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WorkspaceCursorCodec {

    private final SensitiveValueHasher hasher;

    public WorkspaceCursorCodec(SensitiveValueHasher hasher) {
        this.hasher = hasher;
    }

    public String encode(String domain, UUID merchantId, UUID resourceId,
            List<String> anchors) {
        List<String> fields = new ArrayList<>();
        fields.add(merchantId.toString());
        fields.add(resourceId.toString());
        fields.addAll(anchors);
        if (fields.stream().anyMatch(value -> value == null || value.contains("\n"))) {
            throw new IllegalArgumentException("Workspace cursor fields are invalid");
        }
        String unsigned = String.join("\n", fields);
        SensitiveValueHasher.VersionedDigest signature = hasher
                .hmacSha256Keyring("workspace.cursor:" + domain, unsigned).getFirst();
        String encoded = signature.keyVersion() + "\n" + unsigned + "\n" + signature.digest();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encoded.getBytes(StandardCharsets.UTF_8));
    }

    public List<String> decode(String domain, UUID merchantId, UUID resourceId,
            String cursor, int anchorCount) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > 1024) {
            throw invalid();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            String[] fields = decoded.split("\n", -1);
            if (fields.length != anchorCount + 4
                    || !merchantId.equals(UUID.fromString(fields[1]))
                    || !resourceId.equals(UUID.fromString(fields[2]))) {
                throw invalid();
            }
            String unsigned = String.join("\n",
                    java.util.Arrays.copyOfRange(fields, 1, fields.length - 1));
            String expected = hasher.hmacSha256("workspace.cursor:" + domain,
                    unsigned, fields[0]);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    fields[fields.length - 1].getBytes(StandardCharsets.US_ASCII))) {
                throw invalid();
            }
            return List.of(java.util.Arrays.copyOfRange(fields, 3, fields.length - 1));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private ResponseStatusException invalid() {
        return new PublicApiException(PublicApiErrorDescriptor.WORKSPACE_CURSOR_INVALID);
    }
}
