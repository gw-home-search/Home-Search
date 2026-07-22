from __future__ import annotations

import json
import os
from dataclasses import dataclass
from datetime import timedelta
from functools import lru_cache
from pathlib import Path
from typing import Mapping, Protocol

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey
from fastapi import Depends, Header


class AuthenticationRequired(Exception):
    pass


@dataclass(frozen=True)
class AuthenticatedUser:
    user_id: int

    def __post_init__(self) -> None:
        if self.user_id <= 0:
            raise ValueError("user_id must be positive")


@dataclass(frozen=True)
class JwtSettings:
    public_keys: Mapping[str, str]
    issuer: str = "user-service"
    audience: str = "home-search-user-api"
    maximum_lifetime: timedelta = timedelta(minutes=15)

    def __post_init__(self) -> None:
        if not self.issuer or not self.audience or self.maximum_lifetime <= timedelta(0):
            raise ValueError("canonical JWT settings are required")

    @classmethod
    def from_environment(cls) -> JwtSettings:
        encoded = os.getenv("HOME_AI_JWT_PUBLIC_KEY_PATHS", "").strip()
        if not encoded:
            return cls(public_keys={})
        try:
            paths = json.loads(encoded)
            if not isinstance(paths, dict):
                raise ValueError
            keys = {str(key_id): _read_public_key(Path(str(path))) for key_id, path in paths.items()}
            return cls(public_keys=keys)
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as exception:
            raise RuntimeError("invalid AI JWT public key configuration") from exception


class Authenticator(Protocol):
    def authenticate(self, authorization: str | None) -> AuthenticatedUser: ...


class RejectingAuthenticator:
    def authenticate(self, _authorization: str | None) -> AuthenticatedUser:
        raise AuthenticationRequired()


class JwtAuthenticator:
    def __init__(self, settings: JwtSettings) -> None:
        self._settings = settings
        self._public_keys = {key_id: _load_public_key(pem) for key_id, pem in settings.public_keys.items()}

    def authenticate(self, authorization: str | None) -> AuthenticatedUser:
        try:
            token = _bearer_token(authorization)
            header = jwt.get_unverified_header(token)
            if header.get("alg") != "RS256":
                raise AuthenticationRequired()
            key_id = header.get("kid")
            if not isinstance(key_id, str) or key_id not in self._public_keys:
                raise AuthenticationRequired()
            claims = jwt.decode(
                token,
                self._public_keys[key_id],
                algorithms=["RS256"],
                issuer=self._settings.issuer,
                audience=self._settings.audience,
                options={"require": ["exp", "iat", "iss", "aud", "sub", "jti"]},
            )
            audience = claims["aud"]
            if audience != self._settings.audience and audience != [self._settings.audience]:
                raise AuthenticationRequired()
            issued_at = int(claims["iat"])
            expires_at = int(claims["exp"])
            if expires_at <= issued_at or expires_at - issued_at > self._settings.maximum_lifetime.total_seconds():
                raise AuthenticationRequired()
            subject = str(claims["sub"])
            if not subject.isdecimal() or int(subject) <= 0 or claims.get("role") != "USER":
                raise AuthenticationRequired()
            return AuthenticatedUser(user_id=int(subject))
        except AuthenticationRequired:
            raise
        except (KeyError, TypeError, ValueError, jwt.PyJWTError) as exception:
            raise AuthenticationRequired() from exception


@lru_cache(maxsize=1)
def get_authenticator() -> Authenticator:
    settings = JwtSettings.from_environment()
    if not settings.public_keys:
        return RejectingAuthenticator()
    return JwtAuthenticator(settings)


def require_authenticated_user(
    authorization: str | None = Header(default=None, alias="Authorization"),
    authenticator: Authenticator = Depends(get_authenticator),
) -> AuthenticatedUser:
    return authenticator.authenticate(authorization)


def _bearer_token(authorization: str | None) -> str:
    if authorization is None or not authorization.startswith("Bearer "):
        raise AuthenticationRequired()
    token = authorization.removeprefix("Bearer ")
    if not token or token != token.strip() or " " in token:
        raise AuthenticationRequired()
    return token


def _load_public_key(pem: str) -> RSAPublicKey:
    try:
        key = serialization.load_pem_public_key(pem.encode())
    except (TypeError, ValueError) as exception:
        raise ValueError("invalid RSA public key") from exception
    if not isinstance(key, RSAPublicKey) or key.key_size < 2048:
        raise ValueError("RSA public key must be at least 2048 bit")
    return key


def _read_public_key(path: Path) -> str:
    if not path.is_file() or path.stat().st_size > 16 * 1024:
        raise ValueError("invalid public key file")
    return path.read_text(encoding="utf-8")
