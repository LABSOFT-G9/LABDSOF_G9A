-- Synthetic catalogue for the US-06 integration tests (independent of the seed).
-- Buildings: ...b001 = A, ...b002 = B. Plumbing in A, eligible, by name:
--   e003 "Aqua Plumbers" < e001 "Plumbing Co." = e002 "Plumbing Co." (tie: by ID).
-- Not eligible in A: e004 (B only), e005 (inactive), e006 (electrical).
INSERT INTO contractors (id, user_id, display_name, active, synthetic) VALUES
  ('0199f2a0-0000-7000-8000-00000000e001', '0199f2a0-0000-7000-8000-000000000301', 'Plumbing Co.', TRUE, TRUE),
  ('0199f2a0-0000-7000-8000-00000000e002', NULL, 'Plumbing Co.', TRUE, TRUE),
  ('0199f2a0-0000-7000-8000-00000000e003', NULL, 'Aqua Plumbers', TRUE, TRUE),
  ('0199f2a0-0000-7000-8000-00000000e004', NULL, 'Plumbing B Only', TRUE, TRUE),
  ('0199f2a0-0000-7000-8000-00000000e005', NULL, 'Inactive Plumbing', FALSE, TRUE),
  ('0199f2a0-0000-7000-8000-00000000e006', NULL, 'Electrical Services', TRUE, TRUE);
INSERT INTO contractor_specialisations (contractor_id, category) VALUES
  ('0199f2a0-0000-7000-8000-00000000e001', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e002', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e003', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e004', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e005', 'plumbing'),
  ('0199f2a0-0000-7000-8000-00000000e006', 'electrical');
INSERT INTO contractor_coverage (contractor_id, building_id) VALUES
  ('0199f2a0-0000-7000-8000-00000000e001', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e002', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e003', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e004', '0199f2a0-0000-7000-8000-00000000b002'),
  ('0199f2a0-0000-7000-8000-00000000e005', '0199f2a0-0000-7000-8000-00000000b001'),
  ('0199f2a0-0000-7000-8000-00000000e006', '0199f2a0-0000-7000-8000-00000000b001');
