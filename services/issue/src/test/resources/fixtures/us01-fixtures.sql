-- SYNTHETIC test fixtures for US-01-01 (independent of db/seed).
-- Two buildings; residents A1, A2 (building A), B1 (building B);
-- administrators A and B; a revoked administrator of A; a contractor account
-- with no membership.
INSERT IGNORE INTO organisations (id, name) VALUES
  ('0199f2a0-0000-7000-8000-00000000c001', 'Synthetic Property Management');
INSERT IGNORE INTO buildings (id, organisation_id, name) VALUES
  ('0199f2a0-0000-7000-8000-00000000b001', '0199f2a0-0000-7000-8000-00000000c001', 'Synthetic Building A'),
  ('0199f2a0-0000-7000-8000-00000000b002', '0199f2a0-0000-7000-8000-00000000c001', 'Synthetic Building B');
INSERT IGNORE INTO areas (id, building_id, label, kind) VALUES
  ('0199f2a0-0000-7000-8000-00000000a001', '0199f2a0-0000-7000-8000-00000000b001', 'Garage level -1', 'common_area'),
  ('0199f2a0-0000-7000-8000-00000000a002', '0199f2a0-0000-7000-8000-00000000b001', 'Entrance hall', 'common_area'),
  ('0199f2a0-0000-7000-8000-00000000a003', '0199f2a0-0000-7000-8000-00000000b001', 'Unit 2B', 'unit'),
  ('0199f2a0-0000-7000-8000-00000000a101', '0199f2a0-0000-7000-8000-00000000b002', 'Garage', 'common_area');
INSERT IGNORE INTO users (id, display_name) VALUES
  ('0199f2a0-0000-7000-8000-000000000101', 'Synthetic Resident A1'),
  ('0199f2a0-0000-7000-8000-000000000102', 'Synthetic Resident A2'),
  ('0199f2a0-0000-7000-8000-000000000111', 'Synthetic Resident B1'),
  ('0199f2a0-0000-7000-8000-000000000201', 'Synthetic Administrator A'),
  ('0199f2a0-0000-7000-8000-000000000202', 'Synthetic Administrator B'),
  ('0199f2a0-0000-7000-8000-000000000203', 'Synthetic Revoked Administrator A'),
  ('0199f2a0-0000-7000-8000-000000000301', 'Synthetic Contractor');
INSERT IGNORE INTO memberships (id, user_id, building_id, role, status, valid_from, valid_until, version) VALUES
  ('0199f2a0-0000-7000-8000-00000000d001', '0199f2a0-0000-7000-8000-000000000101', '0199f2a0-0000-7000-8000-00000000b001', 'resident', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d002', '0199f2a0-0000-7000-8000-000000000102', '0199f2a0-0000-7000-8000-00000000b001', 'resident', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d003', '0199f2a0-0000-7000-8000-000000000111', '0199f2a0-0000-7000-8000-00000000b002', 'resident', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d004', '0199f2a0-0000-7000-8000-000000000201', '0199f2a0-0000-7000-8000-00000000b001', 'administrator', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d005', '0199f2a0-0000-7000-8000-000000000202', '0199f2a0-0000-7000-8000-00000000b002', 'administrator', 'active', '2026-01-01 00:00:00', NULL, 1),
  ('0199f2a0-0000-7000-8000-00000000d006', '0199f2a0-0000-7000-8000-000000000203', '0199f2a0-0000-7000-8000-00000000b001', 'administrator', 'revoked', '2026-01-01 00:00:00', NULL, 2);
