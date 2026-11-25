# Keycloak for BMC (ADR-005): the stock image, built for MySQL and the /auth
# path, with the realm `bmc` baked in. The deploy copies only compose.yaml to
# the host, so the realm file travels in the image, which is built, scanned and
# signed like the services (.github/services.json).
#
# quay.io/keycloak/keycloak:26.8.2, digest read 2026-11-24.

# Build stage: fix the build-time options, so the container starts with
# `start --optimized` (no re-augmentation at every start).
FROM quay.io/keycloak/keycloak:26.8.2@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3 AS build
ENV KC_DB=mysql \
    KC_HTTP_RELATIVE_PATH=/auth \
    KC_HEALTH_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.2@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3
COPY --from=build /opt/keycloak/ /opt/keycloak/
# The realm, without users and without secrets (placeholders are filled from
# the environment at import). Imported once: an existing realm is skipped.
COPY --chown=1000:0 realm/bmc-realm.json /opt/keycloak/data/import/bmc-realm.json
# Synthetic accounts, imported only when BMC_SEED_SYNTHETIC=true (entrypoint).
COPY --chown=1000:0 realm/bmc-users-0.json /opt/keycloak/synthetic/bmc-users-0.json
COPY --chown=1000:0 --chmod=0555 entrypoint.sh /opt/keycloak/bin/bmc-entrypoint.sh
USER 1000
EXPOSE 8080 9000
ENTRYPOINT ["/opt/keycloak/bin/bmc-entrypoint.sh"]
CMD ["start", "--optimized", "--import-realm"]
