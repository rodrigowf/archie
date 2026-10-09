"""Accounts: sign-in status and sign-in methods for every service Archie uses, and the
``context/.env`` key manager. API: ``api/routes/accounts.py`` (``/api/accounts``, ``/api/env``).
Research notes per service: ``docs/harnesses/authentication.md``."""

from .base import AccountError, AccountService, EnvField, Method, ServiceStatus
from .registry import AccountsManager, default_services

__all__ = ["AccountError", "AccountService", "AccountsManager", "EnvField", "Method", "ServiceStatus", "default_services"]
