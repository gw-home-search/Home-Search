from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from ai_service.auth import (
    AuthenticatedUser,
    AuthenticationRequired,
    JwtAuthenticator,
    JwtSettings,
    RejectingAuthenticator,
    get_authenticator,
)


def key_pair() -> tuple[str, str]:
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    public_pem = private_key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return private_pem, public_pem


def issue(
    private_pem: str,
    *,
    issuer: str = "user-service",
    audience: str = "home-search-user-api",
    subject: str = "42",
    role: str = "USER",
    lifetime: timedelta = timedelta(minutes=15),
) -> str:
    now = datetime.now(UTC)
    return jwt.encode(
        {
            "iss": issuer,
            "aud": audience,
            "sub": subject,
            "jti": "token-1",
            "iat": now,
            "exp": now + lifetime,
            "role": role,
        },
        private_pem,
        algorithm="RS256",
        headers={"kid": "active"},
    )


def test_verifies_canonical_user_token() -> None:
    private_pem, public_pem = key_pair()
    authenticator = JwtAuthenticator(JwtSettings(public_keys={"active": public_pem}))

    authenticated = authenticator.authenticate(f"Bearer {issue(private_pem)}")

    assert authenticated.user_id == 42


@pytest.mark.parametrize(
    ("issuer", "audience"),
    [("wrong", "home-search-user-api"), ("user-service", "wrong")],
)
def test_rejects_wrong_issuer_or_audience(issuer: str, audience: str) -> None:
    private_pem, public_pem = key_pair()
    authenticator = JwtAuthenticator(JwtSettings(public_keys={"active": public_pem}))

    with pytest.raises(AuthenticationRequired):
        authenticator.authenticate(f"Bearer {issue(private_pem, issuer=issuer, audience=audience)}")


def test_rejects_unknown_key_id() -> None:
    private_pem, public_pem = key_pair()
    authenticator = JwtAuthenticator(JwtSettings(public_keys={"other": public_pem}))

    with pytest.raises(AuthenticationRequired):
        authenticator.authenticate(f"Bearer {issue(private_pem)}")


@pytest.mark.parametrize("authorization", [None, "Basic token", "Bearer ", "Bearer token ", "Bearer two tokens"])
def test_rejects_malformed_bearer_header(authorization: str | None) -> None:
    authenticator = JwtAuthenticator(JwtSettings(public_keys={}))

    with pytest.raises(AuthenticationRequired):
        authenticator.authenticate(authorization)


@pytest.mark.parametrize(
    ("subject", "role", "lifetime"),
    [
        ("0", "USER", timedelta(minutes=15)),
        ("not-numeric", "USER", timedelta(minutes=15)),
        ("42", "ADMIN", timedelta(minutes=15)),
        ("42", "USER", timedelta(minutes=16)),
        ("42", "USER", timedelta(seconds=-1)),
    ],
)
def test_rejects_non_user_or_invalid_lifetime(
    subject: str, role: str, lifetime: timedelta
) -> None:
    private_pem, public_pem = key_pair()
    authenticator = JwtAuthenticator(JwtSettings(public_keys={"active": public_pem}))

    with pytest.raises(AuthenticationRequired):
        authenticator.authenticate(
            f"Bearer {issue(private_pem, subject=subject, role=role, lifetime=lifetime)}"
        )


def test_rejects_non_rs256_token() -> None:
    authenticator = JwtAuthenticator(JwtSettings(public_keys={}))
    token = jwt.encode(
        {"sub": "42"},
        "test-secret-that-is-long-enough-for-hs256",
        algorithm="HS256",
        headers={"kid": "active"},
    )

    with pytest.raises(AuthenticationRequired):
        authenticator.authenticate(f"Bearer {token}")


def test_rejects_invalid_user_and_jwt_settings() -> None:
    with pytest.raises(ValueError):
        AuthenticatedUser(user_id=0)
    with pytest.raises(ValueError):
        JwtSettings(public_keys={}, issuer="")
    with pytest.raises(ValueError):
        JwtSettings(public_keys={}, maximum_lifetime=timedelta(0))


def test_loads_public_key_paths_from_environment(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    _private_pem, public_pem = key_pair()
    key_path = tmp_path / "user-public.pem"
    key_path.write_text(public_pem, encoding="utf-8")
    monkeypatch.setenv("HOME_AI_JWT_PUBLIC_KEY_PATHS", json.dumps({"active": str(key_path)}))

    settings = JwtSettings.from_environment()

    assert settings.public_keys == {"active": public_pem}


@pytest.mark.parametrize("encoded", ["[]", "not-json", '{"active":"/missing/key.pem"}'])
def test_rejects_invalid_public_key_path_configuration(
    monkeypatch: pytest.MonkeyPatch, encoded: str
) -> None:
    monkeypatch.setenv("HOME_AI_JWT_PUBLIC_KEY_PATHS", encoded)

    with pytest.raises(RuntimeError, match="invalid AI JWT public key configuration"):
        JwtSettings.from_environment()


def test_default_authenticator_fails_closed_without_keys(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("HOME_AI_JWT_PUBLIC_KEY_PATHS", raising=False)
    get_authenticator.cache_clear()

    authenticator = get_authenticator()

    assert isinstance(authenticator, RejectingAuthenticator)
    with pytest.raises(AuthenticationRequired):
        authenticator.authenticate("Bearer any-token")
    get_authenticator.cache_clear()


def test_rejects_invalid_or_undersized_rsa_public_key() -> None:
    with pytest.raises(ValueError, match="invalid RSA public key"):
        JwtAuthenticator(JwtSettings(public_keys={"active": "not-a-key"}))

    small_key = rsa.generate_private_key(public_exponent=65537, key_size=1024)
    small_public_pem = small_key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    with pytest.raises(ValueError, match="at least 2048 bit"):
        JwtAuthenticator(JwtSettings(public_keys={"active": small_public_pem}))
