from __future__ import annotations

import argparse

from app.db import get_conn


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--note", default="manual_reset")
    args = parser.parse_args()

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("UPDATE activations SET status='reset' WHERE serial=%s AND status='active'", (args.serial,))
            cur.execute(
                """
                UPDATE licenses
                SET status='unused',
                    reset_count=reset_count+1,
                    last_reset_at=NOW()
                WHERE serial=%s
                """,
                (args.serial,),
            )
        conn.commit()
    print(f"reset complete: {args.serial}")


if __name__ == "__main__":
    main()

