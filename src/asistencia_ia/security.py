import base64
from dataclasses import dataclass
import hashlib
import os
from typing import Iterable

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


_ENVELOPE_PREFIX = b"AIA1"
_KEY_ID_BYTES = 8
_NONCE_BYTES = 12


class DataEncryptionError(RuntimeError):
    """Indica que un dato cifrado fue alterado o que su clave no esta disponible."""


def _decode_key(encoded: str) -> bytes:
    try:
        key = base64.b64decode(encoded.strip(), validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError(
            "La clave de cifrado debe estar codificada en Base64."
        ) from error
    if len(key) != 32:
        raise ValueError("La clave de cifrado debe contener exactamente 32 bytes.")
    return key


def _key_id(key: bytes) -> bytes:
    return hashlib.sha256(key).digest()[:_KEY_ID_BYTES]


@dataclass(frozen=True)
class DataCipher:
    """Cifra datos sensibles con AES-256-GCM y admite rotacion de claves."""

    _primary_key: bytes
    _keys: dict[bytes, bytes]

    @classmethod
    def from_base64(
        cls,
        primary_key: str,
        previous_keys: Iterable[str] = (),
    ) -> "DataCipher":
        primary = _decode_key(primary_key)
        keys = [primary, *(_decode_key(item) for item in previous_keys if item.strip())]
        return cls(primary, {_key_id(key): key for key in keys})

    @property
    def primary_key_id(self) -> bytes:
        return _key_id(self._primary_key)

    @staticmethod
    def is_encrypted(value: bytes) -> bool:
        return value.startswith(_ENVELOPE_PREFIX)

    def needs_reencryption(self, value: bytes) -> bool:
        if not self.is_encrypted(value):
            return True
        start = len(_ENVELOPE_PREFIX)
        return value[start : start + _KEY_ID_BYTES] != self.primary_key_id

    def encrypt(self, plaintext: bytes, associated_data: bytes) -> bytes:
        nonce = os.urandom(_NONCE_BYTES)
        ciphertext = AESGCM(self._primary_key).encrypt(
            nonce,
            plaintext,
            associated_data,
        )
        return _ENVELOPE_PREFIX + self.primary_key_id + nonce + ciphertext

    def decrypt(self, envelope: bytes, associated_data: bytes) -> bytes:
        if not self.is_encrypted(envelope):
            return envelope
        header_size = len(_ENVELOPE_PREFIX) + _KEY_ID_BYTES + _NONCE_BYTES
        if len(envelope) <= header_size:
            raise DataEncryptionError("El dato cifrado esta incompleto.")
        offset = len(_ENVELOPE_PREFIX)
        key_identifier = envelope[offset : offset + _KEY_ID_BYTES]
        offset += _KEY_ID_BYTES
        nonce = envelope[offset : offset + _NONCE_BYTES]
        ciphertext = envelope[offset + _NONCE_BYTES :]
        key = self._keys.get(key_identifier)
        if key is None:
            raise DataEncryptionError(
                "La clave requerida para descifrar los datos no esta disponible."
            )
        try:
            return AESGCM(key).decrypt(nonce, ciphertext, associated_data)
        except InvalidTag as error:
            raise DataEncryptionError(
                "La integridad del dato cifrado no pudo verificarse."
            ) from error

    def reencrypt(self, value: bytes, associated_data: bytes) -> bytes:
        plaintext = self.decrypt(value, associated_data)
        return self.encrypt(plaintext, associated_data)
