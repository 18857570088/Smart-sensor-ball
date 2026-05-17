from __future__ import annotations

from contextlib import contextmanager

import pymysql
from pymysql.cursors import DictCursor

from .config import load_settings


@contextmanager
def get_conn():
    settings = load_settings()
    conn = pymysql.connect(
        host=settings.db_host,
        port=settings.db_port,
        user=settings.db_user,
        password=settings.db_password,
        database=settings.db_name,
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=False,
    )
    try:
        yield conn
    finally:
        conn.close()

