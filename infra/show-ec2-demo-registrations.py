#!/usr/bin/env python3
import os
import sqlite3
import sys


DATABASE_PATH = "/var/lib/saved2serve-demo/registrations.sqlite3"


def main():
    if os.geteuid() != 0:
        print("Run this command with sudo to read the private demo database.", file=sys.stderr)
        return 1
    if not os.path.exists(DATABASE_PATH):
        print("No demo registrations have been saved yet.")
        return 0

    with sqlite3.connect(f"file:{DATABASE_PATH}?mode=ro", uri=True) as connection:
        rows = connection.execute(
            """
            SELECT registration_id, received_at, name, email, phone, area_code,
                   source, follow_up_consent
            FROM demo_registrations
            ORDER BY received_at DESC
            """
        ).fetchall()
    if not rows:
        print("No demo registrations have been saved yet.")
        return 0

    for row in rows:
        print(
            "id={0} received_at={1} name={2!r} email={3!r} phone={4!r} "
            "area_code={5!r} source={6!r} follow_up_consent={7}".format(*row)
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
