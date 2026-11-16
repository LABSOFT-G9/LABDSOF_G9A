# python:3.14-slim, digest read 2026-11-15
FROM python:3.14-slim@sha256:9aaa474097d094040649f2162a627c0049b408f52138d980916788d80f04802f
WORKDIR /app
COPY requirements.txt .
# Apply Debian security updates, install the app's dependencies, then remove
# pip: the app does not need it at runtime and its vendored libraries are
# scanned too.
RUN apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && rm -rf /var/lib/apt/lists/* \
 && pip install --no-cache-dir -r requirements.txt \
 && pip uninstall -y pip
COPY app.py .
RUN useradd --system --no-create-home --uid 10001 app
USER 10001
EXPOSE 8080
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "app:app"]
