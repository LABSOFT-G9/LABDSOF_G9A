# EN-02-01 spike: throwaway scripts

The programs behind items 5, 6 and 7 of the [spike record](../EN-02-01-stack-spike.md), kept so the results can be repeated. They are not part of any service and nothing builds them. The passwords in `definitions.json` exist only inside the throwaway broker.

| File | Used for |
|---|---|
| `buf.gen.yaml` | Item 6: generates Java and Python code from `contracts/proto` |
| `RoundTrip.java`, `roundtrip.py` | Item 6: Java writes five events as JSON, Python reads and writes them back, Java compares |
| `definitions.json` | Items 5 and 7: users, permissions, exchange, queues and bindings loaded when the broker starts |
| `pika_spike.py` | Items 5 and 7: what each service user may do, the AI service's consume-publish-acknowledge loop, and the delivery limit |

## Item 6

```bash
buf generate contracts/proto --template buf.gen.yaml     # buf 1.71.0
# Java: put gen/java and RoundTrip.java in a Maven project with protobuf-java and
# protobuf-java-util 4.36.2 (JDK 25), then
#   RoundTrip write <dir-a>
# Python 3.14 with protobuf 7.36.2 (paths at the top of roundtrip.py):
#   python roundtrip.py
#   RoundTrip check <dir-b>
```

## Items 5 and 7

```bash
docker network create spike-net
docker run -d --name spike-rabbit --network spike-net \
  -v "$PWD/definitions.json":/etc/rabbitmq/definitions.json:ro \
  -v "$PWD/rabbitmq.conf":/etc/rabbitmq/conf.d/20-spike.conf:ro \
  rabbitmq:4.4-management
# rabbitmq.conf:
#   definitions.import_backend = local_filesystem
#   definitions.local.path = /etc/rabbitmq/definitions.json
docker run --rm --network spike-net -v "$PWD":/spike:ro python:3.14-slim \
  sh -c 'pip install pika==1.5.0 && python /spike/pika_spike.py'
```

Wait for "Server startup complete" in the broker's log before running anything in its container: a `docker exec` as root before that creates the Erlang cookie with the wrong owner and the broker does not start.
