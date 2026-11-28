package pt.isep.bmc.notification.application.port.out;

import java.util.UUID;

/** New identifiers: UUIDv7, opaque to clients (technical design 4.1). */
public interface IdGenerator {

    UUID newId();
}
