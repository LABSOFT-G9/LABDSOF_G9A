import json, sys, uuid
import pika
from pika import exceptions as ex

print("python", sys.version.split()[0], "pika", pika.__version__)

# The throwaway broker's users are defined in one place: the definitions file it loads.
with open("/spike/definitions.json", encoding="utf-8") as definitions:
    PASSWORDS = {user["name"]: user["password"] for user in json.load(definitions)["users"]}

def connect(user):
    return pika.BlockingConnection(pika.ConnectionParameters("spike-rabbit", 5672, "/", pika.PlainCredentials(user, PASSWORDS[user])))

def envelope(source, type_, data):
    return json.dumps({"specversion": "1.0", "id": str(uuid.uuid4()), "type": type_, "source": source,
                       "subject": "i-1", "time": "2026-11-24T15:00:00Z", "datacontenttype": "application/json",
                       "schemaversion": 1, "aggregateversion": 1, "buildingid": "b-1", "correlationid": "c-spike",
                       "data": data}).encode()

PROPS = pika.BasicProperties(content_type="application/cloudevents+json", delivery_mode=2)

def attempt(label, user, action):
    """Runs one action on a fresh connection and prints what the broker did."""
    conn = connect(user)
    try:
        ch = conn.channel()
        result = action(ch)
        print(f"ALLOWED  {label}" + (f" -> {result}" if result is not None else ""))
    except ex.UnroutableError:
        print(f"RETURNED {label} (no queue bound; mandatory)")
    except ex.NackError:
        print(f"NACKED   {label}")
    except ex.ChannelClosedByBroker as e:
        print(f"REFUSED  {label}: {e.reply_code} {e.reply_text}")
    finally:
        if conn.is_open:
            conn.close()

def publish(routing_key, body):
    def run(ch):
        ch.confirm_delivery()
        ch.basic_publish("bmc.events", routing_key, body, PROPS, mandatory=True)
        return "confirmed"
    return run

print("\n== Publishing, per-service topic permissions")
attempt("issue publishes issue.TriageRequested", "issue",
        publish("issue.TriageRequested", envelope("issue", "TriageRequested", {"issueId": "i-1", "issueVersion": 1, "text": "synthetic"})))
attempt("issue publishes ai-triage.TriageSuggested (another producer's prefix)", "issue",
        publish("ai-triage.TriageSuggested", envelope("ai-triage", "TriageSuggested", {})))
attempt("issue publishes issue.ReportSubmitted (bound to no queue)", "issue",
        publish("issue.ReportSubmitted", envelope("issue", "ReportSubmitted", {})))

print("\n== What a service user without configure permission may do")
attempt("ai-triage declares its own queue passively", "ai-triage",
        lambda ch: f"{ch.queue_declare('ai-triage.requests', passive=True).method.message_count} message(s) waiting")
attempt("ai-triage declares a new queue", "ai-triage", lambda ch: ch.queue_declare("ai-triage.extra", durable=True) and None)
attempt("ai-triage binds its queue to issue.#", "ai-triage",
        lambda ch: ch.queue_bind("ai-triage.requests", "bmc.events", "issue.#") and None)
attempt("ai-triage reads issue.inbox", "ai-triage", lambda ch: ch.basic_get("issue.inbox", auto_ack=False) and None)

print("\n== The AI service's loop: consume, publish the suggestion with a confirm, then acknowledge")
conn = connect("ai-triage")
ch = conn.channel()
ch.confirm_delivery()
ch.basic_qos(prefetch_count=1)
for method, props, body in ch.consume("ai-triage.requests", auto_ack=False, inactivity_timeout=10):
    if method is None:
        print("FAIL no TriageRequested arrived")
        break
    request = json.loads(body)
    print("consumed", request["type"], "from", request["source"], "routing key", method.routing_key,
          "content type", props.content_type, "redelivered", method.redelivered)
    suggestion = envelope("ai-triage", "TriageSuggested",
                          {"issueId": request["data"]["issueId"], "issueVersion": request["data"]["issueVersion"],
                           "category": "other", "urgency": "low", "origin": "rules", "rulesVersion": "rules-v1"})
    ch.basic_publish("bmc.events", "ai-triage.TriageSuggested", suggestion, PROPS, mandatory=True)
    ch.basic_ack(method.delivery_tag)
    print("published TriageSuggested (confirmed, routed) and acknowledged the request")
    break
ch.cancel()
conn.close()

attempt("issue reads the suggestion from issue.inbox", "issue",
        lambda ch: (lambda got: (ch.basic_ack(got[0].delivery_tag), json.loads(got[2])["type"] + " from " + json.loads(got[2])["source"])[1])(ch.basic_get("issue.inbox", auto_ack=False)))

print("\n== Delivery limit 5 with basic.reject and requeue")
attempt("issue publishes a second request", "issue",
        publish("issue.TriageRequested", envelope("issue", "TriageRequested", {"issueId": "i-2", "issueVersion": 1, "text": "synthetic"})))
conn = connect("ai-triage")
ch = conn.channel()
ch.basic_qos(prefetch_count=1)
deliveries = 0
for method, props, body in ch.consume("ai-triage.requests", auto_ack=False, inactivity_timeout=5):
    if method is None:
        break
    deliveries += 1
    headers = props.headers or {}
    print(f"delivery {deliveries}: redelivered={method.redelivered} x-delivery-count={headers.get('x-delivery-count')} x-acquired-count={headers.get('x-acquired-count')}")
    ch.basic_reject(method.delivery_tag, requeue=True)
ch.cancel()
conn.close()
print("deliveries before the queue stopped redelivering:", deliveries)
