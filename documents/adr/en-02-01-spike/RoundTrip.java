package spike;

import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import pt.isep.bmc.contracts.aitriage.v1.TriageSuggested;
import pt.isep.bmc.contracts.dispatch.v1.BookingConfirmed;
import pt.isep.bmc.contracts.dispatch.v1.TimeWindow;
import pt.isep.bmc.contracts.issue.v1.IssueStateChanged;
import pt.isep.bmc.contracts.issue.v1.TriageRequested;
import pt.isep.bmc.contracts.notification.v1.NotificationRequested;

public final class RoundTrip {
    static Map<String, Message> originals() {
        Map<String, Message> m = new LinkedHashMap<>();
        m.put("IssueStateChanged", IssueStateChanged.newBuilder().setIssueId("0199f2a1-0000-7000-8000-000000002001")
                .setBuildingId("0199f2a0-0000-7000-8000-00000000b001").setState("received").setVersion(1)
                .setDispatchable(false).setAreaLabel("Garagem – piso −1 ✓").build());
        m.put("TriageRequested", TriageRequested.newBuilder().setIssueId("i-1").setIssueVersion(1).setBuildingId("b-1")
                .setAreaKind("common_area").setText("Água a pingar do teto 💧\nJunto à tomada.").build());
        m.put("TriageSuggested", TriageSuggested.newBuilder().setIssueId("i-1").setIssueVersion(1).setCategory("plumbing")
                .setUrgency("high").setRationale("Water near electricity.").setNeedsReview(true).setOrigin("fallback")
                .setRulesVersion("rules-v1").setFailureCode("provider_timeout").build());
        m.put("BookingConfirmed", BookingConfirmed.newBuilder().setIssueId("i-1")
                .setWindow(TimeWindow.newBuilder().setStart(Timestamp.newBuilder().setSeconds(1794128400L))
                        .setEnd(Timestamp.newBuilder().setSeconds(1794139200L)))
                .setSource("calendar").setInterventionState("booked").build());
        m.put("NotificationRequested", NotificationRequested.newBuilder().setNotificationId("n-1").setSourceEventId("e-1")
                .addRecipientUserIds("u-1").addRecipientUserIds("u-2").setTemplateKey("issue.triaged")
                .putParams("issueId", "i-1").putParams("areaLabel", "Hall").setPriority("normal").build());
        return m;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[1]);
        JsonFormat.Printer printer = JsonFormat.printer().alwaysPrintFieldsWithNoPresence().omittingInsignificantWhitespace();
        if (args[0].equals("write")) {
            for (var e : originals().entrySet()) {
                Files.writeString(dir.resolve(e.getKey() + ".json"), printer.print(e.getValue()), StandardCharsets.UTF_8);
                System.out.println("java wrote " + e.getKey() + ": " + printer.print(e.getValue()));
            }
            return;
        }
        int failed = 0;
        for (var e : originals().entrySet()) {
            Message.Builder builder = e.getValue().newBuilderForType();
            String json = Files.readString(dir.resolve(e.getKey() + ".json"), StandardCharsets.UTF_8);
            JsonFormat.parser().merge(json, builder);
            boolean same = builder.build().equals(e.getValue());
            System.out.println((same ? "PASS " : "FAIL ") + e.getKey() + " (Python wrote: " + json.replace("\n", " ") + ")");
            if (!same) failed++;
        }
        // A message Python built on its own, and an unknown field as a newer producer would send.
        TriageSuggested.Builder fromPython = TriageSuggested.newBuilder();
        JsonFormat.parser().merge(Files.readString(dir.resolve("PythonBuilt.json")), fromPython);
        boolean ok = fromPython.getOrigin().equals("rules") && !fromPython.hasModelVersion() && fromPython.getIssueVersion() == 7;
        System.out.println((ok ? "PASS " : "FAIL ") + "PythonBuilt TriageSuggested");
        if (!ok) failed++;
        IssueStateChanged.Builder tolerant = IssueStateChanged.newBuilder();
        String withUnknown = "{\"issueId\":\"i\",\"futureField\":1,\"category\":null}";
        try { JsonFormat.parser().merge(withUnknown, IssueStateChanged.newBuilder()); System.out.println("NOTE strict parser accepted an unknown field"); }
        catch (Exception ex) { System.out.println("NOTE strict parser refuses an unknown field: " + ex.getClass().getSimpleName()); }
        JsonFormat.parser().ignoringUnknownFields().merge(withUnknown, tolerant);
        System.out.println("NOTE ignoringUnknownFields accepts it; category present=" + tolerant.hasCategory());
        if (failed > 0) System.exit(1);
    }
}
