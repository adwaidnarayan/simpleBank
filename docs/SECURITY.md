# Security and encryption

## What is encrypted

The complete serialized bank state is encrypted using AES-256-GCM: customer names, email addresses, phone numbers, card numbers, accounts, balances, transaction history, administrative logs, and ID counters. Every save uses a fresh 96-bit random nonce and a 128-bit authentication tag. The versioned header is authenticated. Only ciphertext is written to temporary files. Files are replaced atomically after successful encryption.

Loading verifies authenticity before deserialization. Wrong keys, missing keys, and modified ciphertext fail without resetting the bank. Legacy Java-serialized bank files are validated with the existing object filter, then atomically encrypted on load. No plaintext migration backup is created. The pre-card backup in this workspace was also migrated to encrypted storage.

## Key storage and recovery

Keys are generated with SecureRandom and stored outside the project in `%USERPROFILE%/.simple-bank-keys`. Each data file has its own key, named with the SHA-256 hash of its normalized absolute path. Keys and their directory have owner-only permissions on Windows/POSIX. Keys are not embedded in source code or the encrypted bank file. The key directory can be overridden with the Java property `-Dbank.keyDirectory=...`.

Back up the data file AND the key directory separately. Losing the key permanently prevents recovery. Copying only bank.dat is no longer a complete backup. To restore at the same absolute path, restore its key as well. To move to a different path, copy the original key to the filename for the SHA-256 hash of the new normalized absolute data path (UTF-8), or restore at the original path. Stop the server before backup or restore. The standalone FileBankRepository main method can load/migrate a legacy backup without applying domain changes.

An OS user or administrator able to read the key can decrypt the data. This is disk-file protection, not protection against malware running as your user. Application memory and values intentionally displayed in the UI must be decrypted for use. Previously generated screenshots, clipboard contents, and exported documents are not encrypted by the bank repository. Replacing old files does not guarantee secure erasure of previous disk blocks, external backups, or version history.

## Browser connection encryption

HTTPS is supported using a PKCS12 keystore containing a certificate trusted by your browser, with a subject alternative name matching 127.0.0.1. Set BANK_TLS_PASSWORD to the keystore password in the server environment, then run:

```text
java -Dbank.tls.keystore=C:/path/to/server.p12 -cp out bank.BankApplication 8443
```

Open https://127.0.0.1:8443. TLS 1.2 and 1.3 are enabled. No certificates are automatically added to the machine trust store. Invalid keystore configuration fails startup. The existing local site remains on HTTP until a trusted certificate is configured; browser traffic on that endpoint is not encrypted. Do not expose this local application to the internet.

## Other controls and limits

Responses disable caching and referrer transmission. A nonce-based Content Security Policy restricts scripts/styles and blocks framing, base URL changes, and external form submission. Existing HTML escaping, server validation, one-time form tokens, and loopback binding remain enabled.

Customer and administrator logins now enforce server-side roles. Customer HTML contains only their own account data; Admin endpoints and account creation require an administrator session. Customer transactions and history queries enforce ownership. The administrator password is stored as a salted PBKDF2-HMAC-SHA256 hash (210,000 iterations), not a plaintext production credential. The requested assignment password remains weak and publicly specified; this is not a production credential setup. Customer name/card login is the requested assignment mechanism, not multi-factor authentication.

Random 256-bit session IDs are stored in HttpOnly, SameSite=Strict cookies, with Secure added under HTTPS. Sessions rotate on login, expire after 30 idle minutes or 8 hours total, and are revoked at logout/restart. CSRF and one-time form tokens are tied to individual sessions. Five login failures per remote address cause a one-minute cooldown. The default HTTP transport remains unencrypted; use the documented HTTPS configuration before deployment outside local assignment use.

## Verification

Run `./test.ps1`: 76 existing checks plus 8 encryption checks. These cover encrypted round trips, absence of plaintext fields, fresh ciphertext, tamper detection, wrong/missing keys, unchanged data on failure, and migration. HTTPS configuration requires a trusted certificate and was not tested with a production certificate.

Cryptographic implementation reference: [Oracle JCA guide](https://docs.oracle.com/en/java/javase/26/security/java-cryptography-architecture-jca-reference-guide.html).
