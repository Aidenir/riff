You have to edit:

./data/dev.diegovsky.Riff.metainfo.xml: add new update metadata and changelog
./meson.build: update version here too



## Android signing key

Release APKs are signed with one key. Losing it means users must uninstall
Riff before an update will install, so it is kept in three places:

- **Bitwarden** (Viktor's vault): the backup of record. It holds the keystore
  file `riff-release.jks` and the values from `release-signing.env`
  (keystore password, key alias `riff`, key password).
- **`~/.riff/`** on Viktor's Mac: `riff-release.jks` and
  `release-signing.env`, used for local builds:
  `source ~/.riff/release-signing.env && ./scripts/build-android.sh release`.
- **GitHub Actions secrets** on `Aidenir/riff`, used by `riff-android.yml`:
  `RIFF_KEYSTORE_BASE64` (the keystore, base64), `RIFF_KEYSTORE_PASSWORD`,
  `RIFF_KEY_ALIAS` and `RIFF_KEY_PASSWORD`.

To set up a new machine, restore both files from Bitwarden into `~/.riff/`
(`chmod 600` them). To restore the CI secrets, run with the `Aidenir` account:

```sh
source ~/.riff/release-signing.env
base64 -i "$RIFF_KEYSTORE" | gh secret set RIFF_KEYSTORE_BASE64 -R Aidenir/riff
printf '%s' "$RIFF_KEYSTORE_PASSWORD" | gh secret set RIFF_KEYSTORE_PASSWORD -R Aidenir/riff
printf '%s' "$RIFF_KEY_ALIAS" | gh secret set RIFF_KEY_ALIAS -R Aidenir/riff
printf '%s' "$RIFF_KEY_PASSWORD" | gh secret set RIFF_KEY_PASSWORD -R Aidenir/riff
```

The key's SHA-256 certificate fingerprint is
`38:09:cc:9d:ce:87:6c:6a:14:10:3b:9f:a2:ce:5e:4b:5d:3b:f6:33:25:b9:c2:66:93:04:f8:e6:a0:b1:4d:58`;
`apksigner verify --print-certs <apk>` shows it for any signed build.
