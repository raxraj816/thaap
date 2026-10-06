"""One India-calendar date for governance evidence on every developer and CI host.

THAAP's dated learning/review records use Asia/Kolkata (UTC+05:30, no DST).
Payment timestamps, analytics windows and transaction-time semantics remain UTC.
"""
from datetime import date, datetime, timedelta, timezone

REVIEW_TIMEZONE = timezone(timedelta(hours=5, minutes=30), name="Asia/Kolkata")


def review_today(instant: datetime | None = None) -> date:
    """Resolve a real aware instant; never infer a calendar from the host's TZ."""
    if instant is None:
        instant = datetime.now(timezone.utc)
    if instant.tzinfo is None or instant.utcoffset() is None:
        raise ValueError("governance review instant must be timezone-aware")
    return instant.astimezone(REVIEW_TIMEZONE).date()
