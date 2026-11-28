package pt.isep.bmc.notification.domain.notification;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One kind of notification: the parameters it takes, the sentence shown to the
 * recipient and the in-app path it links to. Templates are rendered by the
 * service from {@code templateKey} and {@code params}; producers and clients
 * never supply the text (ADR-007).
 *
 * @param key      the {@code templateKey} of {@code NotificationRequested}
 * @param required parameters that must be present
 * @param optional parameters that may be present
 * @param title    the sentence shown in the inbox
 * @param linkPath the client path, with {@code {param}} placeholders for required
 *                 {@link ParamKind#ID} parameters; {@code null} when the recipient
 *                 has no page to open yet
 */
public record Template(
        String key, Map<String, ParamKind> required, Map<String, ParamKind> optional, String title, String linkPath) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    public Template {
        Objects.requireNonNull(key, "key");
        required = Map.copyOf(required);
        optional = Map.copyOf(optional);
        Objects.requireNonNull(title, "title");
        if (linkPath != null) {
            Matcher placeholders = PLACEHOLDER.matcher(linkPath);
            while (placeholders.find()) {
                if (required.get(placeholders.group(1)) != ParamKind.ID) {
                    throw new IllegalArgumentException(
                            "link placeholder " + placeholders.group(1) + " must be a required ID parameter");
                }
            }
        }
    }

    /** The kind of a parameter this template accepts. */
    public Optional<ParamKind> kindOf(String param) {
        return Optional.ofNullable(required.getOrDefault(param, optional.get(param)));
    }

    /**
     * The link for valid parameters. Only ID parameters are substituted, so the
     * link can never carry anything but identifiers.
     */
    public Optional<String> link(Map<String, String> params) {
        if (linkPath == null) {
            return Optional.empty();
        }
        Matcher placeholders = PLACEHOLDER.matcher(linkPath);
        StringBuilder link = new StringBuilder();
        while (placeholders.find()) {
            String value = params.get(placeholders.group(1));
            if (value == null) {
                return Optional.empty();
            }
            placeholders.appendReplacement(link, Matcher.quoteReplacement(value));
        }
        placeholders.appendTail(link);
        return Optional.of(link.toString());
    }
}
