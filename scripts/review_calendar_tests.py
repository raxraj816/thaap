"""Forced calendar boundaries for dated governance evidence, not payment event time."""
import datetime as dt
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import review_calendar


class ReviewCalendarTests(unittest.TestCase):
    def test_utc_rollover_month_year_and_leap_partitions(self):
        cases = (
            ("2026-10-05T18:29:59+00:00", "2026-10-05"),
            ("2026-10-05T18:30:00+00:00", "2026-10-06"),
            ("2026-10-05T21:13:38+00:00", "2026-10-06"),
            ("2026-10-06T00:00:00+00:00", "2026-10-06"),
            ("2026-10-31T18:30:00+00:00", "2026-11-01"),
            ("2026-12-31T18:30:00+00:00", "2027-01-01"),
            ("2028-02-28T18:30:00+00:00", "2028-02-29"),
            ("2028-02-29T18:30:00+00:00", "2028-03-01"),
        )
        for instant, expected in cases:
            with self.subTest(instant=instant):
                self.assertEqual(dt.date.fromisoformat(expected),
                    review_calendar.review_today(dt.datetime.fromisoformat(instant)))

    def test_equal_instants_with_different_offsets_share_date(self):
        for instant in ("2026-10-05T21:13:38+00:00", "2026-10-06T02:43:38+05:30",
                        "2026-10-05T14:13:38-07:00", "2026-10-06T10:13:38+13:00"):
            with self.subTest(instant=instant):
                self.assertEqual(dt.date(2026, 10, 6),
                    review_calendar.review_today(dt.datetime.fromisoformat(instant)))

    def test_naive_time_is_rejected_instead_of_using_host_timezone(self):
        with self.assertRaises(ValueError):
            review_calendar.review_today(dt.datetime(2026, 10, 5, 21, 13))

    def test_default_clock_reads_an_aware_utc_instant(self):
        with patch.object(review_calendar, "datetime") as clock:
            clock.now.return_value = dt.datetime(2026, 10, 5, 21, 13, tzinfo=dt.timezone.utc)
            self.assertEqual(dt.date(2026, 10, 6), review_calendar.review_today())
            clock.now.assert_called_once_with(dt.timezone.utc)

    def test_host_timezone_cannot_change_frozen_default_clock(self):
        code = """
import datetime as dt
from unittest.mock import patch
import review_calendar
with patch.object(review_calendar, 'datetime') as clock:
    clock.now.return_value = dt.datetime(2026, 10, 5, 21, 13, tzinfo=dt.timezone.utc)
    print(review_calendar.review_today())
"""
        for zone in ("UTC", "Asia/Kolkata", "America/Los_Angeles", "Pacific/Auckland"):
            with self.subTest(zone=zone):
                result = subprocess.run([sys.executable, "-c", code],
                    cwd=pathlib.Path(__file__).parent,
                    env={**os.environ, "TZ": zone}, capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("2026-10-06", result.stdout.strip())

    def test_real_cli_entrypoints_accept_india_today_on_utc_runner(self):
        from technical_debt_check_tests import valid_register_entry
        from vision_coverage_check_tests import valid_matrix, ROADMAP, VISION
        root = pathlib.Path(__file__).resolve().parents[1]
        code = """
import datetime as dt
import pathlib
import runpy
import sys
from unittest.mock import patch
sys.path.insert(0, str(pathlib.Path('scripts').resolve()))
import review_calendar
script = sys.argv[1]
sys.argv = sys.argv[1:]
with patch.object(review_calendar, 'datetime') as clock:
    clock.now.return_value = dt.datetime(2026, 10, 5, 21, 13, 38, tzinfo=dt.timezone.utc)
    runpy.run_path(script, run_name='__main__')
"""
        with tempfile.TemporaryDirectory() as directory:
            fixtures = pathlib.Path(directory)
            matrix = fixtures / "vision.md"
            entry = "## VP-001" + valid_matrix().partition("## VP-001")[2]
            matrix.write_text("# Vision fixture\n\n- Status: ACTIVE\n\n" + "\n".join(
                entry.replace("VP-001", f"VP-{number:03d}").replace("2026-07-27", "2026-10-06")
                for number in range(1, 24)))
            register = fixtures / "debt.md"
            register.write_text(valid_register_entry().replace("2026-06-27", "2026-10-06"))
            roadmap = fixtures / "roadmap.md"
            roadmap.write_text(ROADMAP + "\nTD-0001\n" + "\n".join(f"VP-{number:03d}" for number in range(1, 24)))
            vision = fixtures / "product.md"
            vision.write_text(VISION)
            cases = (
                ("scripts/vision-coverage-check.py", "--matrix", str(matrix), "--vision", str(vision)),
                ("scripts/technical-debt-check.py", "--register", str(register)),
            )
            for arguments in cases:
                with self.subTest(script=arguments[0]):
                    result = subprocess.run([sys.executable, "-c", code, *arguments,
                        "--master-plan", str(roadmap)], cwd=root,
                        env={**os.environ, "TZ": "UTC"}, capture_output=True, text=True, timeout=10)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn("check passed:", result.stdout)
