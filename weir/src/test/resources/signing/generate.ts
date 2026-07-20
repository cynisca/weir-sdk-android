// R1 cross-language signing fixture generator (docs/android-support-plan.md §9 R1, §6 D3).
//
// Produces a COMMITTED, shared, bytes-on-disk fixture that both the iOS
// `BundleManager` tests and the forthcoming Kotlin `BundleManager` test verify
// against the SAME artifact — proving the Kotlin verify path accepts exactly
// what the TypeScript `services/api` signer produces.
//
// This uses the REAL signer (`services/api/src/signing.ts`) so the signature and
// per-file SHA-256 hashes are genuine, not hand-rolled.
//
// Run (from anywhere; tsx resolves the relative import):
//   pnpm --filter @x/api exec tsx sdk-android/weir/src/test/resources/signing/generate.ts
// or:
//   ./node_modules/.bin/tsx sdk-android/weir/src/test/resources/signing/generate.ts
//
// Deterministic: it loads a FIXED, committed throwaway Ed25519 private key from
// `test-signing-key.pkcs8.b64` (never a real signing key) rather than generating
// a fresh one, so re-running yields byte-identical output.

import { createPrivateKey, createPublicKey, verify as edVerify, type KeyObject } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Real signer — the exact code services/api runs. Imported by relative path so
// this script is self-contained and needs no workspace package resolution.
import {
  signManifest,
  sha256Hex,
  signingPayload,
  rawPublicKeyBytes,
  ED25519_SPKI_PREFIX,
  type ManifestFile,
} from "../../../../../../services/api/src/signing.ts";

const here = dirname(fileURLToPath(import.meta.url));

// --- Fixed throwaway keypair (committed; NOT a real signing key) -------------
const privateKeyB64 = readFileSync(join(here, "test-signing-key.pkcs8.b64"), "utf8").trim();
const privateKey: KeyObject = createPrivateKey({
  key: Buffer.from(privateKeyB64, "base64"),
  format: "der",
  type: "pkcs8",
});
const publicKey: KeyObject = createPublicKey(privateKey as unknown as Parameters<typeof createPublicKey>[0]);
const publicKeyRawBase64 = rawPublicKeyBytes(publicKey).toString("base64");

// --- The bundle contents (tiny, ASCII-only, deterministic) -------------------
// ASCII only on purpose: JS `Array.sort()`, Swift `String.sorted()`, and Kotlin
// `String.compareTo` all agree on ASCII ordering; non-ASCII could diverge.
const BUNDLE_ID = "2026-07-17-r1fixture";
const SPEC_VERSION = 3; // matches services/api's stamped SPEC_VERSION (BundleManager supports {0,3})
const VERSION = 2; // monotonic publish counter, signed (R2-S1); >1 so a downgrade test has room below it
const CDN = "https://cdn.example.com/bundles/2026-07-17-r1fixture";

const files: Record<string, string> = {
  "index.html":
    "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>R1 fixture</title></head>\n" +
    "<body><div id=\"weir-root\">R1 cross-language signing fixture</div></body></html>\n",
  "flows/cob_intake.json":
    JSON.stringify({ flowId: "cob_intake", specVersion: SPEC_VERSION, entry: "welcome" }, null, 2) + "\n",
};

// Deterministic manifest-file entries. `url` is part of the SIGNED payload, so
// it is fixed here and both verifiers reconstruct the payload from these exact
// strings (offline — they never fetch the URL).
const manifestFiles: ManifestFile[] = Object.keys(files)
  .sort()
  .map((path) => ({
    path,
    url: `${CDN}/${path}`,
    sha256: sha256Hex(Buffer.from(files[path], "utf8")),
  }));

const signature = signManifest(privateKey, BUNDLE_ID, SPEC_VERSION, VERSION, manifestFiles);

interface BundleUpdateManifest {
  specVersion: number;
  bundleId: string;
  version: number;
  files: ManifestFile[];
  signature: string;
}

const goodManifest: BundleUpdateManifest = {
  specVersion: SPEC_VERSION,
  bundleId: BUNDLE_ID,
  version: VERSION,
  files: manifestFiles,
  signature,
};

// --- Emit ---------------------------------------------------------------------
function emitBundleFiles(dir: string, contents: Record<string, string>): void {
  for (const [path, body] of Object.entries(contents)) {
    const dest = join(dir, path);
    mkdirSync(dirname(dest), { recursive: true });
    writeFileSync(dest, body, "utf8");
  }
}

function writeManifest(dir: string, manifest: BundleUpdateManifest): void {
  mkdirSync(dir, { recursive: true });
  // Pretty JSON with a trailing newline. NOTE: the manifest JSON layout is NOT
  // what is signed — only `signingPayload` is. So JSON key order / whitespace
  // here is cosmetic and does not affect verification.
  writeFileSync(join(dir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n", "utf8");
}

// Clean prior output so re-runs are byte-deterministic.
for (const d of ["good", "tampered-signature", "tampered-file"]) {
  rmSync(join(here, d), { recursive: true, force: true });
}

// good/: valid signature, every file hashes to its manifest entry.
emitBundleFiles(join(here, "good"), files);
writeManifest(join(here, "good"), goodManifest);

// tampered-signature/: identical bundle files, but one byte of the raw 64-byte
// signature is flipped (still valid base64). Verify MUST reject on signature.
const sigBytes = Buffer.from(signature, "base64");
sigBytes[0] ^= 0x01;
const tamperedSigManifest: BundleUpdateManifest = { ...goodManifest, signature: sigBytes.toString("base64") };
emitBundleFiles(join(here, "tampered-signature"), files);
writeManifest(join(here, "tampered-signature"), tamperedSigManifest);

// tampered-file/: manifest is byte-identical to good/ (signature still valid),
// but index.html has one byte changed on disk, so its SHA-256 no longer matches
// the manifest. Verify passes the signature check; the per-file hash check MUST
// reject. This exercises the OTHER rejection path.
const tamperedFiles: Record<string, string> = {
  ...files,
  "index.html": files["index.html"].replace("R1 cross-language", "R1 TAMPERED-language"),
};
emitBundleFiles(join(here, "tampered-file"), tamperedFiles);
writeManifest(join(here, "tampered-file"), goodManifest); // manifest unchanged on purpose

// The raw 32-byte public key, base64 — exactly WeirUpdateConfig.publicKeyRawBase64.
writeFileSync(join(here, "public-key.raw.b64"), publicKeyRawBase64 + "\n", "utf8");

// --- Self-check: round-trip through the TS verifier --------------------------
// Reconstruct a verifiable public KeyObject from the RAW 32 bytes the same way
// an SDK does (raw bytes -> SPKI DER via the fixed prefix), to prove the raw
// public key we emit is sufficient to verify.
function publicKeyFromRawBase64(rawB64: string): KeyObject {
  const raw = Buffer.from(rawB64, "base64");
  const spki = Buffer.concat([ED25519_SPKI_PREFIX, raw]);
  return createPublicKey({ key: spki, format: "der", type: "spki" });
}
const verifyKey = publicKeyFromRawBase64(publicKeyRawBase64);

function loadManifest(dir: string): BundleUpdateManifest {
  return JSON.parse(readFileSync(join(here, dir, "manifest.json"), "utf8")) as BundleUpdateManifest;
}

// Full verify == signature valid AND every on-disk file hashes to its entry.
function fullVerify(dir: string): { specSupported: boolean; signatureValid: boolean; hashesValid: boolean } {
  const m = loadManifest(dir);
  const specSupported = [0, 3].includes(m.specVersion);
  const payload = signingPayload(m.bundleId, m.specVersion, m.version, m.files);
  let signatureValid = false;
  try {
    signatureValid = edVerify(null, payload, verifyKey, Buffer.from(m.signature, "base64"));
  } catch {
    signatureValid = false;
  }
  let hashesValid = true;
  for (const f of m.files) {
    const onDisk = readFileSync(join(here, dir, f.path));
    if (sha256Hex(onDisk) !== f.sha256) hashesValid = false;
  }
  return { specSupported, signatureValid, hashesValid };
}

const good = fullVerify("good");
const tsig = fullVerify("tampered-signature");
const tfile = fullVerify("tampered-file");

console.log("=== R1 signing fixture generated ===");
console.log("bundleId:              ", BUNDLE_ID);
console.log("specVersion:           ", SPEC_VERSION);
console.log("publicKeyRawBase64:    ", publicKeyRawBase64);
console.log("signature (good):      ", signature);
console.log("signed payload bytes:  ", JSON.stringify(signingPayload(BUNDLE_ID, SPEC_VERSION, VERSION, manifestFiles).toString("utf8")));
console.log("");
console.log("good/                accept? spec=%s sig=%s hashes=%s  -> %s",
  good.specSupported, good.signatureValid, good.hashesValid,
  good.specSupported && good.signatureValid && good.hashesValid ? "ACCEPT ✅" : "REJECT ❌");
console.log("tampered-signature/  accept? spec=%s sig=%s hashes=%s  -> %s",
  tsig.specSupported, tsig.signatureValid, tsig.hashesValid,
  tsig.specSupported && tsig.signatureValid && tsig.hashesValid ? "ACCEPT ❌ (BUG)" : "REJECT ✅");
console.log("tampered-file/       accept? spec=%s sig=%s hashes=%s  -> %s",
  tfile.specSupported, tfile.signatureValid, tfile.hashesValid,
  tfile.specSupported && tfile.signatureValid && tfile.hashesValid ? "ACCEPT ❌ (BUG)" : "REJECT ✅");

const ok =
  good.specSupported && good.signatureValid && good.hashesValid &&
  tsig.signatureValid === false &&
  tfile.signatureValid === true && tfile.hashesValid === false;
if (!ok) {
  console.error("\nFIXTURE SELF-CHECK FAILED — the TS verifier did not behave as expected.");
  process.exit(1);
}
console.log("\nSelf-check OK: good ACCEPTED; both tampered variants REJECTED.");
