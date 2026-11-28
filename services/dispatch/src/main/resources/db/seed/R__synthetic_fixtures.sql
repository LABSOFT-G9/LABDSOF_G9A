-- Synthetic fixtures for local and dev, loaded only when BMC_SEED_SYNTHETIC=true
-- (FlywaySeedConfig). Every value is invented; nothing describes a real
-- person, company or building. Re-applied when this file changes; rows are
-- never deleted here.

-- 1. The synthetic contractor catalogue (US-06). Only Synthetic Plumbing Co.
--    has a Keycloak account (synthetic.contractor@bmc.example, sub ...0301).
--    The others have no login (user_id NULL), so approving a request for one
--    of them is refused with 422 CONTRACTOR_HAS_NO_ACCOUNT (US-07). Each has a
--    reference in the calendar simulator (US-08).
--    Buildings: ...b001 = Synthetic Building A, ...b002 = Synthetic Building B,
--    as in Issue's seed.
INSERT INTO contractors (id, user_id, display_name, active, synthetic, calendar_ref) VALUES
  ('0199f2a0-0000-7000-8000-00000000e001', '0199f2a0-0000-7000-8000-000000000301', 'Synthetic Plumbing Co.', TRUE, TRUE, 'SIM-CTR-001'),
  ('0199f2a0-0000-7000-8000-00000000e002', NULL, 'Synthetic Electrical Services', TRUE, TRUE, 'SIM-CTR-002'),
  ('0199f2a0-0000-7000-8000-00000000e003', NULL, 'Synthetic Lift Maintenance', TRUE, TRUE, 'SIM-CTR-003'),
  ('0199f2a0-0000-7000-8000-00000000e004', NULL, 'Synthetic Aqua Repairs', TRUE, TRUE, 'SIM-CTR-004'),
  ('0199f2a0-0000-7000-8000-00000000e005', NULL, 'Synthetic Drain Masters', FALSE, TRUE, 'SIM-CTR-005'),
  ('0199f2a0-0000-7000-8000-00000000e006', NULL, 'Synthetic Aqua Plumbers', TRUE, TRUE, 'SIM-CTR-006')
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), display_name = VALUES(display_name), active = VALUES(active),
                        synthetic = VALUES(synthetic), calendar_ref = VALUES(calendar_ref);

INSERT INTO contractor_specialisations (contractor_id, category) VALUES
  ('0199f2a0-0000-7000-8000-00000000e001', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e002', 'electrical'),
  ('0199f2a0-0000-7000-8000-00000000e003', 'lift'),
  ('0199f2a0-0000-7000-8000-00000000e004', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e005', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e006', 'plumbing')
ON DUPLICATE KEY UPDATE category = category;

-- Eligible for a plumbing issue in Building A: e006 then e001 (by name).
-- Not eligible: e004 (Building B only), e005 (inactive), e002 and e003 (other trades).
INSERT INTO contractor_coverage (contractor_id, building_id) VALUES
  ('0199f2a0-0000-7000-8000-00000000e001', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e001', '0199f2a0-0000-7000-8000-00000000b002'),
  ('0199f2a0-0000-7000-8000-00000000e002', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e003', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e003', '0199f2a0-0000-7000-8000-00000000b002'),
  ('0199f2a0-0000-7000-8000-00000000e004', '0199f2a0-0000-7000-8000-00000000b002'),
  ('0199f2a0-0000-7000-8000-00000000e005', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e006', '0199f2a0-0000-7000-8000-00000000b001')
ON DUPLICATE KEY UPDATE building_id = building_id;

-- 2. TEMPORARY stand-in for the projections, until RabbitMQ is wired
--    (EN-02-02): normally only Issue's events write these tables. A row that an
--    event has already updated is left as it is.
--
--    The memberships Issue seeds (same IDs and versions as Issue's
--    R__synthetic_fixtures.sql): Administrator A (...0201) administers
--    Building A, Administrator B (...0202) Building B.
INSERT INTO membership_projection (membership_id, user_id, building_id, role, active, version, applied_at) VALUES
  ('0199f2a0-0000-7000-8000-00000000d001', '0199f2a0-0000-7000-8000-000000000101', '0199f2a0-0000-7000-8000-00000000b001', 'resident', TRUE, 1, '2026-01-01 00:00:00'),
  ('0199f2a0-0000-7000-8000-00000000d002', '0199f2a0-0000-7000-8000-000000000102', '0199f2a0-0000-7000-8000-00000000b001', 'resident', TRUE, 1, '2026-01-01 00:00:00'),
  ('0199f2a0-0000-7000-8000-00000000d003', '0199f2a0-0000-7000-8000-000000000111', '0199f2a0-0000-7000-8000-00000000b002', 'resident', TRUE, 1, '2026-01-01 00:00:00'),
  ('0199f2a0-0000-7000-8000-00000000d004', '0199f2a0-0000-7000-8000-000000000201', '0199f2a0-0000-7000-8000-00000000b001', 'administrator', TRUE, 1, '2026-01-01 00:00:00'),
  ('0199f2a0-0000-7000-8000-00000000d005', '0199f2a0-0000-7000-8000-000000000202', '0199f2a0-0000-7000-8000-00000000b002', 'administrator', TRUE, 1, '2026-01-01 00:00:00')
ON DUPLICATE KEY UPDATE membership_id = membership_id;

--    One dispatchable plumbing issue in Building A. It exists in Dispatch ONLY:
--    Issue has no issue with this ID, so the client cannot open it from an
--    issue page. Use its ID directly:
--    GET /api/dispatch/contractors?issueId=0199f2a0-0000-7000-8000-00000000f001
INSERT INTO issue_projection
  (issue_id, building_id, state, version, dispatchable, category, urgency, area_label, published_summary_id, applied_at)
VALUES
  ('0199f2a0-0000-7000-8000-00000000f001', '0199f2a0-0000-7000-8000-00000000b001', 'triaged', 2, TRUE,
   'plumbing', 'high', 'Garage level -1', NULL, '2026-01-01 00:00:00')
ON DUPLICATE KEY UPDATE issue_id = issue_id;
