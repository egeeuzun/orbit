# Release signing

Release APKs are **not** signed by Gradle. They are built unsigned and then
signed manually with `apksigner`, because the release carries a
**SigningCertificateLineage** (APK Signature Scheme v3.1) that Gradle cannot
produce.

## Why a lineage

Releases up to `v1.228` were signed with the Android debug key. From `v2.0.0`
the project uses a real RSA-4096 key (`CN=Ege Uzun, O=Orbit`). Signing with
the new key alone would break in-place updates for everyone who already has
the app. The lineage links the old certificate to the new one, so:

- Android 13+ (API 33+) verifies against the **new** key (v3.1 block);
- older releases verify against the **previous** certificate (v1/v2/v3.0
  blocks), which keeps updates installable without a reinstall.

`apksigner` targets rotation at API 33+ by default. Do not lower it with
`--rotation-min-sdk-version` without a reason: v3.0 rotation has known issues
on API 28-32, which is why v3.1 exists.

## Secrets

Held by the maintainer only, outside the repository (`.gitignore` covers
`*.jks`, `keystore.properties`):

| File | Purpose |
|---|---|
| `orbit-release.jks` | the signing key (alias `orbit`) |
| `orbit-lineage.bin` | proof-of-rotation, **required for every future release** |
| `orbit-keystore-pass.txt` | keystore password |

Losing `orbit-release.jks` or `orbit-lineage.bin` means no user can ever
update in place again. Back both up.

If `keystore.properties` exists at the repo root, Gradle will sign the release
with that single key and **no** lineage — that is the wrong output for a
public release. Keep it absent.

## Building a release

```sh
export JAVA_HOME=/path/to/android-studio/jbr   # a JDK the AGP version supports
BT=$ANDROID_HOME/build-tools/37.0.0
KEYDIR=/path/to/keys
PASS=$(cat "$KEYDIR/orbit-keystore-pass.txt")

./gradlew clean assembleRelease            # produces *-unsigned.apk

for abi in arm64-v8a armeabi-v7a x86_64 universal; do
  "$BT/zipalign" -p -f 4 \
    app/build/outputs/apk/release/app-$abi-release-unsigned.apk \
    /tmp/aligned-$abi.apk

  "$BT/apksigner" sign \
    --lineage "$KEYDIR/orbit-lineage.bin" \
    --ks "$ANDROID_SDK_HOME/.android/debug.keystore" \
      --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --next-signer --ks "$KEYDIR/orbit-release.jks" \
      --ks-key-alias orbit --ks-pass pass:"$PASS" --key-pass pass:"$PASS" \
    --min-sdk-version 21 \
    --out orbit-<version>-$abi.apk /tmp/aligned-$abi.apk
done
```

The oldest signer in the lineage (the debug key) must be passed first: v1/v2
signing requires it, and old devices validate against it.

## Verifying before publishing

```sh
apksigner verify --print-certs --min-sdk-version 21 --max-sdk-version 32 APK  # -> Android Debug cert
apksigner verify --print-certs --min-sdk-version 33 APK                       # -> CN=Ege Uzun, O=Orbit
```

Both must pass. The first proves existing users can still update; the second
proves the rotation took effect. Drop the `.idsig` files (v4 signatures) that
`apksigner` writes next to the output — they are only used by
`adb install --incremental` and should not be attached to a release.

## When the debug key can be dropped

Once `minSdk` is raised to 33, v1/v2/v3.0 blocks are no longer needed and the
release can be signed with the new key alone, without a lineage. Until then
the old certificate remains a valid signer for pre-Android-13 devices.
