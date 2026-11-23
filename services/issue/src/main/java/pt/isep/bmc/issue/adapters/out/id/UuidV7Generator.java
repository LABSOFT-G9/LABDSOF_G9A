package pt.isep.bmc.issue.adapters.out.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import pt.isep.bmc.issue.application.port.out.IdGenerator;

/**
 * UUIDv7 (RFC 9562, section 5.7): 48-bit Unix time in milliseconds, then
 * version and variant bits, then 74 random bits. Time-ordered, so new rows
 * land at the end of the primary key index; opaque to clients.
 */
@Component
class UuidV7Generator implements IdGenerator {

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID newId() {
        long millis = clock.millis();
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        long randA = ((bytes[0] & 0xFFL) << 4) | ((bytes[1] & 0xF0L) >>> 4); // 12 bits
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
        long lsb = 0L;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10
        return new UUID(msb, lsb);
    }
}
