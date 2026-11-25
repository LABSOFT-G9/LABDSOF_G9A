import sys, pathlib
sys.path.insert(0, "/spike/gen/python")
import google.protobuf
from google.protobuf import json_format
from bmc.issue.v1 import issue_state_changed_pb2, triage_requested_pb2
from bmc.aitriage.v1 import triage_suggested_pb2
from bmc.dispatch.v1 import booking_confirmed_pb2
from bmc.notification.v1 import notification_requested_pb2

print("python", sys.version.split()[0], "protobuf", google.protobuf.__version__)
TYPES = {
    "IssueStateChanged": issue_state_changed_pb2.IssueStateChanged,
    "TriageRequested": triage_requested_pb2.TriageRequested,
    "TriageSuggested": triage_suggested_pb2.TriageSuggested,
    "BookingConfirmed": booking_confirmed_pb2.BookingConfirmed,
    "NotificationRequested": notification_requested_pb2.NotificationRequested,
}
src, out = pathlib.Path("/spike/rt/java"), pathlib.Path("/spike/rt/python")
for name, cls in TYPES.items():
    msg = json_format.Parse((src / f"{name}.json").read_text(encoding="utf-8"), cls())
    text = json_format.MessageToJson(msg, always_print_fields_with_no_presence=True, indent=None, ensure_ascii=False)
    (out / f"{name}.json").write_text(text, encoding="utf-8")
    print("python read and wrote", name)
state = json_format.Parse((src / "IssueStateChanged.json").read_text(encoding="utf-8"), TYPES["IssueStateChanged"]())
assert state.dispatchable is False and not state.HasField("category") and state.area_label == "Garagem – piso −1 ✓", state
own = triage_suggested_pb2.TriageSuggested(issue_id="i-9", issue_version=7, category="other", urgency="low",
                                           rationale="No rule matched.", needs_review=True, origin="rules", rules_version="rules-v1")
(out / "PythonBuilt.json").write_text(json_format.MessageToJson(own, always_print_fields_with_no_presence=True), encoding="utf-8")
print("python built its own TriageSuggested")
