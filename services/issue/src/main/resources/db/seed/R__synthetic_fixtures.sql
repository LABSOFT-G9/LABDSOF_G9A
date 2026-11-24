-- SYNTHETIC DATA ONLY: no real people, addresses or buildings.
-- Loaded only when BMC_SEED_SYNTHETIC=true (local and dev, never production).
-- Two buildings and every role needed by the US-01 tests (resident, another
-- resident of the same building, a resident of another building, one
-- administrator per building, a contractor account and a platform staff
-- account, both with no membership).
-- IDs follow the contract annex convention (…0101 resident, …0201
-- administrator, …0301 contractor account, …0401 platform staff, …b001
-- building). User IDs are the Keycloak `sub` values of the synthetic accounts
-- in infra/keycloak/realm/bmc-users-0.json: keep the two files in step.
-- A migration writes no events: the service announces these memberships to
-- the outbox at start (SeededMembershipAnnouncer).
-- Repeatable migration: re-runs when this file changes, so every statement is
-- an upsert.

INSERT INTO organisations (id, name) VALUES
  ('0199f2a0-0000-7000-8000-00000000c001', 'Synthetic Property Management')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO buildings (id, organisation_id, name) VALUES
  ('0199f2a0-0000-7000-8000-00000000b001', '0199f2a0-0000-7000-8000-00000000c001', 'Synthetic Building A'),
  ('0199f2a0-0000-7000-8000-00000000b002', '0199f2a0-0000-7000-8000-00000000c001', 'Synthetic Building B')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO areas (id, building_id, label, kind) VALUES
  ('0199f2a0-0000-7000-8000-00000000a001', '0199f2a0-0000-7000-8000-00000000b001', 'Garage level -1', 'common_area'),
  ('0199f2a0-0000-7000-8000-00000000a002', '0199f2a0-0000-7000-8000-00000000b001', 'Entrance hall', 'common_area'),
  ('0199f2a0-0000-7000-8000-00000000a003', '0199f2a0-0000-7000-8000-00000000b001', 'Unit 2B', 'unit'),
  ('0199f2a0-0000-7000-8000-00000000a101', '0199f2a0-0000-7000-8000-00000000b002', 'Garage', 'common_area'),
  ('0199f2a0-0000-7000-8000-00000000a102', '0199f2a0-0000-7000-8000-00000000b002', 'Roof terrace', 'common_area')
ON DUPLICATE KEY UPDATE label = VALUES(label), kind = VALUES(kind);

INSERT INTO users (id, display_name) VALUES
  ('0199f2a0-0000-7000-8000-000000000101', 'Synthetic Resident A1'),
  ('0199f2a0-0000-7000-8000-000000000102', 'Synthetic Resident A2'),
  ('0199f2a0-0000-7000-8000-000000000111', 'Synthetic Resident B1'),
  ('0199f2a0-0000-7000-8000-000000000201', 'Synthetic Administrator A'),
  ('0199f2a0-0000-7000-8000-000000000202', 'Synthetic Administrator B'),
  ('0199f2a0-0000-7000-8000-000000000301', 'Synthetic Contractor'),
  ('0199f2a0-0000-7000-8000-000000000401', 'Synthetic Platform Staff')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO memberships (id, user_id, building_id, role, status, valid_from, valid_until, version) VALUES
  ('0199f2a0-0000-7000-8000-00000000d001', '0199f2a0-0000-7000-8000-000000000101', '0199f2a0-0000-7000-8000-00000000b001', 'resident', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d002', '0199f2a0-0000-7000-8000-000000000102', '0199f2a0-0000-7000-8000-00000000b001', 'resident', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d003', '0199f2a0-0000-7000-8000-000000000111', '0199f2a0-0000-7000-8000-00000000b002', 'resident', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d004', '0199f2a0-0000-7000-8000-000000000201', '0199f2a0-0000-7000-8000-00000000b001', 'administrator', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d005', '0199f2a0-0000-7000-8000-000000000202', '0199f2a0-0000-7000-8000-00000000b002', 'administrator', 'active', '2026-01-01 00:00:00', NULL, 1)
-- Never overwrite a membership changed at run time (versions only go up).
ON DUPLICATE KEY UPDATE id = id;
