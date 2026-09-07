# R1 cross-language signing fixture — v4

**Purpose (docs/android-support-plan.md §9 risk R1, §6 D3):** a committed,
bytes-on-disk artifact proving the Kotlin `BundleManager` end-to-end
fetch→verify→stage path accepts **exactly** what the TypeScript
`services/api` v4 config signer produces. This fixture drives
`BundleManagerTest.kt`'s full `checkForUpdate` path (real per-file SHA-256
downloads + staging), which is a stronger proof than a pure signature check
alone.

For the pure cross-language `verify()` parity gate (no I/O, 7 cases, shared
verbatim with iOS), see `packages/evals/fixtures/v4/signing.json` and
`ConfigSigningParityTest.kt` — that fixture is generated separately
(`packages/evals` RFC-010 §8.1 generator) and is the canonical multi-language
parity artifact. This directory's fixture exists purely to exercise
`BundleManager`'s real download/stage/promote state machine end-to-end
against genuine signed bytes; it is Android-only, not shared with iOS.

If a Kotlin/BouncyCastle verifier gets any detail below wrong, every real
manifest is silently rejected and OTA is silently dead (it fails **closed**,
so it *looks* fine on the embedded bundle). Match this spec byte-for-byte.

## v4 migration (2026-07-30)

This fixture was regenerated from its original v3 shape (`bundleId`,
`specVersion: 3`, no `registryManifestVersion`, an `index.html` bundle file)
to the v4 shape (RFC-010 §5): `configId`, `specVersion: 4`,
`registryManifestVersion`, and a `config.json` bundle file in place of
`index.html` (v4 serves a config, not an HTML/JS bundle — "There is no
HTML/JS file to sign; that is the entire point of the pivot," RFC-010 §5).
Regenerated via `generate.ts`, which now calls `services/api/src/signing.ts`'s
`signConfigManifest` (wrapping `@x/spec`'s real `configSigningPayload`)
instead of the retired v3 `signManifest`/`signingPayload` pair. The throwaway
test-signing key (`test-signing-key.pkcs8.b64`) and the fixture's own
`configId` (`2026-07-17-r1fixture`) are unchanged from the original v3
fixture — only the manifest shape and signed payload moved to v4.

## Files

```
signing/
  generate.ts                 # regenerator (uses the REAL services/api v4 signer)
  test-signing-key.pkcs8.b64  # FIXED throwaway Ed25519 private key (PKCS8 DER, base64).
                              #   TEST KEY ONLY — never a real signing key. Committed
                              #   solely for deterministic fixture regeneration.
  public-key.raw.b64          # raw 32-byte Ed25519 public key, standard base64 (+ newline).
                              #   Exactly WeirUpdateConfig.publicKeyRawBase64 format.
  good/
    manifest.json             # a validly signed BundleUpdateManifest (v4 shape)
    config.json                # bundle file; SHA-256 matches its manifest entry
    flows/cob_intake.json     # bundle file; SHA-256 matches its manifest entry
  tampered-signature/         # identical files; one byte of the raw signature flipped.
    manifest.json             #   -> signature verification MUST fail
    config.json
    flows/cob_intake.json
  tampered-file/              # manifest byte-identical to good/ (signature still valid),
    manifest.json             #   but config.json has one byte changed on disk
    config.json                #   -> per-file SHA-256 check MUST fail
    flows/cob_intake.json
```

The Kotlin `BundleManagerTest` should assert:
- `good/` → **ACCEPT** (spec supported, signature valid, every file hashes correctly).
- `tampered-signature/` → **REJECT** on signature.
- `tampered-file/` → signature *valid* but **REJECT** on per-file SHA-256 mismatch.

## Fixture values (this committed instance)

| | |
|---|---|
| `configId` | `2026-07-17-r1fixture` |
| `specVersion` | `4` (supported set is `{4}`) |
| `version` | `2` |
| `registryManifestVersion` | `1` |
| `publicKeyRawBase64` | `ZCmlLMjUGy+GNgv+kI1ASfUOhUF8b0wPeGFdRkPiLT0=` |
| `signature` (good) | `dHEHPLXiAufX5grE6SSHKntbGqIugcYcwN3hde17vrEliJf8vzKv3zrCoVmZAsLlc8hKdXgqUxkxL/V5lk9PCA==` |

## The manifest shape (`BundleUpdateManifest`, v4)

```json
{
  "specVersion": 4,
  "configId": "2026-07-17-r1fixture",
  "version": 2,
  "registryManifestVersion": 1,
  "files": [
    { "path": "...", "url": "https://...", "sha256": "<lowercase hex>" }
  ],
  "signature": "<base64 raw 64-byte Ed25519 signature>"
}
```

`files` JSON order is **not** significant — the signed payload sorts them itself
(see below). The manifest JSON's key order and whitespace are cosmetic; **only the
`signingPayload` bytes are signed**, never the JSON text.

## The signed payload — the load-bearing detail

The Ed25519 signature is computed over these **UTF-8 bytes** (source of truth:
`packages/spec/src/config-signing.ts` `configSigningPayload`, mirrored by
`sdk-ios/Sources/WeirCore/Persistence/BundleManager.swift`
`BundleUpdateManifest.signingPayload` and this SDK's own
`BundleUpdateManifest.signingPayload` in `BundleManager.kt`):

1. Line `configId:<configId>`
2. Line `specVersion:<specVersion>`   (integer, no padding, e.g. `4`)
3. Line `version:<version>`          (integer, no padding)
4. Line `registryManifestVersion:<registryManifestVersion>`   (integer, no padding)
5. Then, for each file, the line `<path>:<url>:<sha256>`
   — these file lines are **sorted lexicographically as whole strings**, and the
   sort is applied to the file lines **only** (the four header lines are prepended
   *after* sorting, in that fixed order).
6. All lines joined with a single `\n` (0x0A). **No trailing newline.**

For this fixture the exact signed string is (one line, `\n` shown literally):

```
configId:2026-07-17-r1fixture\nspecVersion:4\nversion:2\nregistryManifestVersion:1\nconfig.json:https://cdn.example.com/bundles/2026-07-17-r1fixture/config.json:30511cf79407a1657e718c7cb60c36587bf6abbc3dcef1d149510d0675b46577\nflows/cob_intake.json:https://cdn.example.com/bundles/2026-07-17-r1fixture/flows/cob_intake.json:5f42503ab812b77b80150bf99d2c9a5263367d4766d89475d4e80647094bb3de
```

Reference Kotlin payload reconstruction:

```kotlin
fun signingPayload(configId: String, specVersion: Int, version: Int, registryManifestVersion: Int, files: List<ManifestFile>): ByteArray {
    val fileLines = files.map { "${it.path}:${it.url}:${it.sha256}" }.sorted()
    val lines = listOf(
        "configId:$configId",
        "specVersion:$specVersion",
        "version:$version",
        "registryManifestVersion:$registryManifestVersion",
    ) + fileLines
    return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
}
```

`List<String>.sorted()` in Kotlin uses natural `String.compareTo` (UTF-16 code-unit
order). JS `Array.sort()` (the signer) and Swift `String.sorted()` (iOS verifier)
agree with it **for ASCII**. This fixture keeps all `path`/`url` values ASCII on
purpose — do not introduce non-ASCII paths without re-checking all three orderings.

## The `configId` anti-replay binding

`configId` is the **first** line of the signed payload, so a signed manifest cannot
be replayed against a different config id by an attacker who controls transport but
not the signing key. Additionally, both native SDKs bind the manifest to its
on-disk staging directory: **`manifest.configId` must equal the staged directory's
name** (iOS: `verifyStagedManifestOnDisk` checks `manifest.configId ==
directory.lastPathComponent`; Kotlin: `manifest.configId != directory.name` in
`BundleManager.verifyStagedManifestOnDisk`). `specVersion` and
`registryManifestVersion` are likewise in the payload so a manifest can't be
replayed across spec versions or against a different registry-gating state.

## Key encoding

- **Public key:** the **raw 32-byte** Ed25519 public key, **standard base64**
  (RFC 4648 — `+` `/` with `=` padding), **NOT** base64url. This is what a host
  pastes into `WeirUpdateConfig` as `publicKeyRawBase64`.
  - BouncyCastle (recommended per §6 D3, because `java.security` Ed25519 is API 33+
    only and minSdk is 26):
    ```kotlin
    val raw = Base64.decode(publicKeyRawBase64, Base64.DEFAULT) // 32 bytes
    val pub = Ed25519PublicKeyParameters(raw, 0)
    val verifier = Ed25519Signer().apply { init(false, pub) }
    verifier.update(payload, 0, payload.size)
    val valid = verifier.verifySignature(signatureBytes) // 64 bytes
    ```
  - If you ever go through `java.security` (API 33+ only): wrap the raw 32 bytes in
    the 12-byte SPKI DER prefix `302a300506032b6570032100` and load via
    `X509EncodedKeySpec`. BouncyCastle's `Ed25519PublicKeyParameters` takes the raw
    bytes directly — prefer it.
- **Signature:** raw **64-byte** Ed25519 signature, **standard base64** (padded),
  as the `signature` field. Decode with `Base64.decode(sig, Base64.DEFAULT)` /
  `java.util.Base64.getDecoder()` — **not** the url-safe decoder.

## Hash algorithm

Each file's `sha256` is the **lowercase hex** SHA-256 of the **exact file bytes**
served (`services/api` `sha256Hex`; iOS `BundleManager.sha256Hex`; Kotlin
`BundleManager.sha256Hex`). Kotlin:

```kotlin
MessageDigest.getInstance("SHA-256").digest(fileBytes)
    .joinToString("") { "%02x".format(it) }
```

## Subtleties a Kotlin/BouncyCastle verifier is most likely to get wrong

1. **Ed25519, not Ed25519ph.** Pure EdDSA over the raw payload — no pre-hash. Use
   BouncyCastle `Ed25519Signer`, **not** `Ed25519phSigner`. (Node `crypto.sign(null,
   …, ed25519Key)` and CryptoKit `Curve25519.Signing` are both pure Ed25519.)
2. **base64 vs base64url.** Both the public key and the signature are **standard**
   base64 with `+`/`/` and `=` padding. Using the url-safe alphabet/decoder breaks
   verification. (The committed values contain `+` and `/` — a url-safe decoder
   will mangle them.)
3. **Raw 32-byte key, not SPKI/PEM.** `publicKeyRawBase64` is the bare 32 bytes.
   Do not feed it to `X509EncodedKeySpec` without the 12-byte SPKI prefix.
4. **No trailing newline** in the signed payload, and lines are joined with a
   single `\n` (LF, `0x0A`) — never `\r\n`.
5. **Sort the file lines, in-place-of-order, before prepending** the four header
   lines; sort the **full `path:url:sha256` line**, not just `path`. The header
   lines are never part of the sort.
6. **`specVersion`/`version`/`registryManifestVersion` render as bare integers**
   (e.g. `4`), no quotes, no padding.
7. **UTF-8** encoding of the payload string.
8. **The signed bytes are `signingPayload`, not the manifest JSON.** JSON key order
   / pretty-printing / whitespace in `manifest.json` is irrelevant to verification —
   do not attempt to canonicalize the JSON.
9. **Per-file SHA-256 is a separate gate from the signature.** `tampered-file/`
   has a valid signature but a mismatched file hash; a verifier that only checks the
   signature would wrongly accept it. Check both.
10. **`@JavascriptInterface`/threading** is irrelevant here (this is offline
    verification), but the `configId == staged-dir-name` binding (§ anti-replay)
    still applies to the rehydration path.

## Regenerating

```
./node_modules/.bin/tsx sdk-android/weir/src/test/resources/signing/generate.ts
```

Deterministic — it reuses the committed fixed key, so output is byte-identical.
The script self-checks (verifies `good/`, rejects both tampered variants) and exits
non-zero if the TS verifier ever disagrees with this spec.
