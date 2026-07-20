# R1 cross-language signing fixture

**Purpose (docs/android-support-plan.md §9 risk R1, §6 D3):** a committed,
bytes-on-disk artifact proving the Kotlin `BundleManager` verify path accepts
**exactly** what the TypeScript `services/api` signer produces — the same fixture
the iOS `BundleManager` tests can verify. Both SDKs prove parity against **one
committed file**, not two independently generated ones that could drift.

If a Kotlin/BouncyCastle verifier gets any detail below wrong, every real manifest
is silently rejected and OTA is silently dead (it fails **closed**, so it *looks*
fine on the embedded bundle). Match this spec byte-for-byte.

## Files

```
signing/
  generate.ts                 # regenerator (uses the REAL services/api signer)
  test-signing-key.pkcs8.b64  # FIXED throwaway Ed25519 private key (PKCS8 DER, base64).
                              #   TEST KEY ONLY — never a real signing key. Committed
                              #   solely for deterministic fixture regeneration.
  public-key.raw.b64          # raw 32-byte Ed25519 public key, standard base64 (+ newline).
                              #   Exactly WeirUpdateConfig.publicKeyRawBase64 format.
  good/
    manifest.json             # a validly signed BundleUpdateManifest
    index.html                # bundle file; SHA-256 matches its manifest entry
    flows/cob_intake.json     # bundle file; SHA-256 matches its manifest entry
  tampered-signature/         # identical files; one byte of the raw signature flipped.
    manifest.json             #   -> signature verification MUST fail
    index.html
    flows/cob_intake.json
  tampered-file/              # manifest byte-identical to good/ (signature still valid),
    manifest.json             #   but index.html has one byte changed on disk
    index.html                #   -> per-file SHA-256 check MUST fail
    flows/cob_intake.json
```

The Kotlin `BundleManagerTest` should assert:
- `good/` → **ACCEPT** (spec supported, signature valid, every file hashes correctly).
- `tampered-signature/` → **REJECT** on signature.
- `tampered-file/` → signature *valid* but **REJECT** on per-file SHA-256 mismatch.

## Fixture values (this committed instance)

| | |
|---|---|
| `bundleId` | `2026-07-17-r1fixture` |
| `specVersion` | `3` (supported set is `{0, 3}`) |
| `publicKeyRawBase64` | `ZCmlLMjUGy+GNgv+kI1ASfUOhUF8b0wPeGFdRkPiLT0=` |
| `signature` (good) | `AEpiaSHfO8XoF+FE0UTNNSBmQrEVJFx2shtTaF8Qq2OBvDzwOgCIkD60ohU2N+B8nFg/4ew5ANyGHxDCnguvBA==` |

## The manifest shape (`BundleUpdateManifest`)

```json
{
  "specVersion": 3,
  "bundleId": "2026-07-17-r1fixture",
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
`services/api/src/signing.ts` `signingPayload`, mirrored by
`sdk-ios/.../BundleManager.swift` `BundleUpdateManifest.signingPayload`):

1. Line `bundleId:<bundleId>`
2. Line `specVersion:<specVersion>`   (integer, no padding, e.g. `3`)
3. Then, for each file, the line `<path>:<url>:<sha256>`
   — these file lines are **sorted lexicographically as whole strings**, and the
   sort is applied to the file lines **only** (the `bundleId` and `specVersion`
   lines are prepended *after* sorting, in that fixed order).
4. All lines joined with a single `\n` (0x0A). **No trailing newline.**

For this fixture the exact signed string is (one line, `\n` shown literally):

```
bundleId:2026-07-17-r1fixture\nspecVersion:3\nflows/cob_intake.json:https://cdn.example.com/bundles/2026-07-17-r1fixture/flows/cob_intake.json:6a8228f3ce9bd712f12bbe49815031974977ffa2d44cbc0f73b0caeb79bfb543\nindex.html:https://cdn.example.com/bundles/2026-07-17-r1fixture/index.html:d3afc599c03838097b657741fe339a3a1ad9322b27a98c59ec7df989e7c2127c
```

Reference Kotlin payload reconstruction:

```kotlin
fun signingPayload(bundleId: String, specVersion: Int, files: List<ManifestFile>): ByteArray {
    val fileLines = files.map { "${it.path}:${it.url}:${it.sha256}" }.sorted()
    val lines = listOf("bundleId:$bundleId", "specVersion:$specVersion") + fileLines
    return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
}
```

`List<String>.sorted()` in Kotlin uses natural `String.compareTo` (UTF-16 code-unit
order). JS `Array.sort()` (the signer) and Swift `String.sorted()` (iOS verifier)
agree with it **for ASCII**. This fixture keeps all `path`/`url` values ASCII on
purpose — do not introduce non-ASCII paths without re-checking all three orderings.

## The `bundleId` anti-replay binding

`bundleId` is the **first** line of the signed payload, so a signed manifest cannot
be replayed against a different bundle id by an attacker who controls transport but
not the signing key. Additionally, both native SDKs bind the manifest to its
on-disk staging directory: **`manifest.bundleId` must equal the staged directory's
name** (iOS: `verifyStagedManifestOnDisk` checks `manifest.bundleId ==
directory.lastPathComponent`). The Kotlin `BundleManager` must enforce the same.
`specVersion` is likewise in the payload so a manifest can't be replayed across
spec versions.

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
served (`services/api` `sha256Hex`; iOS `BundleManager.sha256Hex`). Kotlin:

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
5. **Sort the file lines, in-place-of-order, before prepending** `bundleId`/
   `specVersion`; sort the **full `path:url:sha256` line**, not just `path`. The
   two header lines are never part of the sort.
6. **`specVersion` renders as a bare integer** (`3`), no quotes, no padding.
7. **UTF-8** encoding of the payload string.
8. **The signed bytes are `signingPayload`, not the manifest JSON.** JSON key order
   / pretty-printing / whitespace in `manifest.json` is irrelevant to verification —
   do not attempt to canonicalize the JSON.
9. **Per-file SHA-256 is a separate gate from the signature.** `tampered-file/`
   has a valid signature but a mismatched file hash; a verifier that only checks the
   signature would wrongly accept it. Check both.
10. **`@JavascriptInterface`/threading** is irrelevant here (this is offline
    verification), but the `bundleId == staged-dir-name` binding (§ anti-replay)
    still applies to the rehydration path.

## Regenerating

```
./node_modules/.bin/tsx sdk-android/weir/src/test/resources/signing/generate.ts
```

Deterministic — it reuses the committed fixed key, so output is byte-identical.
The script self-checks (verifies `good/`, rejects both tampered variants) and exits
non-zero if the TS verifier ever disagrees with this spec.
