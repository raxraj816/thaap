"""One pager for every cursor-paged THAAP investigation route.

Closes the client half of `TD-0006`. Until the navigation routes agreed on a single
collection field name, an integrator writing a pager had to branch per route, and a
per-route branch is exactly where pagination defects hide: the branch that is only
exercised by the least-used route is the one that silently drops a page.

Every cursor-paged route now publishes its rows as ``data`` and signals finality with
a null ``nextCursor``, so one loop is sufficient.
"""

from __future__ import annotations

from typing import Any, Callable, Iterator


# A traversal is bounded so a server that never stops returning a cursor cannot spin a
# client forever. The limit is deliberately generous: it is a safety stop, not a
# pagination policy, and hitting it means something is wrong rather than large.
MAXIMUM_PAGES = 1_000


class PaginationError(RuntimeError):
    """Raised when a page cannot be traversed safely."""


def iterate_pages(
    fetch: Callable[[str | None], Any],
    *,
    maximum_pages: int = MAXIMUM_PAGES,
) -> Iterator[Any]:
    """Yield each page of a cursor-paged route in order.

    ``fetch`` receives the cursor for the next page and ``None`` for the first.
    """
    cursor: str | None = None
    seen: set[str] = set()
    for _ in range(maximum_pages):
        page = fetch(cursor)
        yield page
        cursor = getattr(page, "next_cursor", None)
        if cursor is None:
            return
        # A repeated cursor means the traversal is not advancing. Looping forever on a
        # payment's timeline would look like a hang rather than a bug, so it fails loudly.
        if cursor in seen:
            raise PaginationError("THAAP returned a repeated pagination cursor")
        seen.add(cursor)
    raise PaginationError(
        f"THAAP pagination exceeded {maximum_pages} pages without a final page")


def iterate_rows(
    fetch: Callable[[str | None], Any],
    *,
    maximum_pages: int = MAXIMUM_PAGES,
) -> Iterator[Any]:
    """Yield every row across every page, in server order."""
    for page in iterate_pages(fetch, maximum_pages=maximum_pages):
        rows = getattr(page, "data", None)
        if rows is None:
            raise PaginationError(
                "THAAP page did not publish its rows as 'data'; the route is not one of "
                "the cursor-paged investigation surfaces")
        yield from rows
