from __future__ import annotations

import hashlib
import hmac
import secrets
import string


DIGITS = set(string.digits)


def normalize_serial(value: str) -> str:
    serial = "".join(ch for ch in value.strip() if ch in DIGITS)
    if len(serial) != 11:
        raise ValueError("serial must be 11 digits")
    return serial


def normalize_code(value: str) -> str:
    code = "".join(ch for ch in value.strip() if ch in DIGITS)
    if len(code) != 8:
        raise ValueError("code must be 8 digits")
    return code


def hash_code(serial: str, code: str, pepper: str) -> str:
    payload = f"{serial}:{code}:{pepper}".encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def verify_code(serial: str, code: str, stored_hash: str, pepper: str) -> bool:
    expected = hash_code(serial, code, pepper)
    return hmac.compare_digest(expected, stored_hash)


def make_activation_token() -> str:
    return secrets.token_hex(32)


def luhn_checksum(number_without_check: str) -> int:
    total = 0
    reverse_digits = list(map(int, reversed(number_without_check)))
    for index, digit in enumerate(reverse_digits):
        if index % 2 == 0:
            doubled = digit * 2
            total += doubled - 9 if doubled > 9 else doubled
        else:
            total += digit
    return (10 - (total % 10)) % 10

