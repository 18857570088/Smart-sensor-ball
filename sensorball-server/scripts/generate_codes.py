from __future__ import annotations

import argparse
import csv
import secrets
from pathlib import Path

from app.security import hash_code, luhn_checksum


def make_serial(product_prefix: str, batch_code: str, sequence: int) -> str:
    body = f"{product_prefix}{batch_code}{sequence:04d}"
    check = luhn_checksum(body)
    return f"{body}{check}"


def make_code() -> str:
    return f"{secrets.randbelow(100_000_000):08d}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--product-prefix", required=True, help="2 digit numeric prefix, e.g. 01")
    parser.add_argument("--batch-code", required=True, help="4 digit numeric batch code, e.g. 2604")
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--start-sequence", type=int, default=1)
    parser.add_argument("--product-code", default="SSB01")
    parser.add_argument("--batch-no", default=None)
    parser.add_argument("--pepper", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    if len(args.product_prefix) != 2 or not args.product_prefix.isdigit():
        raise SystemExit("product-prefix must be 2 digits")
    if len(args.batch_code) != 4 or not args.batch_code.isdigit():
        raise SystemExit("batch-code must be 4 digits")

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.writer(fh)
        writer.writerow(["serial", "activation_code", "code_hash", "product_code", "batch_no"])
        for offset in range(args.count):
            sequence = args.start_sequence + offset
            serial = make_serial(args.product_prefix, args.batch_code, sequence)
            code = make_code()
            code_hash = hash_code(serial, code, args.pepper)
            writer.writerow([serial, code, code_hash, args.product_code, args.batch_no or args.batch_code])


if __name__ == "__main__":
    main()
