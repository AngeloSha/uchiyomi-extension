# Uchiyomi extension for Mihon, Tachiyomi and Tachimanga

Read your own [Uchiyomi](https://github.com/AngeloSha/uchiyomi) library from Mihon (Android), any
Tachiyomi fork, Tachimanga (iOS) or Suwayomi — with one API token.

## Add the store

Paste this URL where your app asks for an **extension repo** (Mihon: *Settings → Browse → Extension
repos*; Tachimanga: *Settings → Extension repos*):

```
https://raw.githubusercontent.com/AngeloSha/uchiyomi-extension/repo/index.min.json
```

Then install **Uchiyomi** from the extensions list. The store carries every index format the Mihon family
reads (`index.min.json`, `repo.json`, `index.json`, `index.pb`), so the same URL works everywhere.

## Set it up

1. In Uchiyomi, open **Profile → Security → API tokens** and create a token with the **read** scope.
   That is all the extension needs.
2. In the extension's settings: **Server address** (your Uchiyomi URL, e.g. `https://manga.example.com`)
   and **API token**. Each is checked the moment you save it — you get a toast saying *Uchiyomi found at …*
   and *Connected as …*, or the reason it did not work.
3. Browse. **Popular** is your favourites first, then whatever you are furthest behind on; **Latest** is
   recently updated; search takes free text plus filters for genre (all of the ticked ones), status, read
   state and library.

Two more switches: **Skip repeated pages** drops pages Uchiyomi has seen repeated across three or more
chapters of a series — credit cards, "read on our site" plates — but never leaves a chapter empty; and
**Show 18+ libraries** includes libraries marked adult.

Requires Uchiyomi **v0.29.0** or newer (the release that lets an API token fetch images and search).

## What it cannot do

**Reading progress does not sync back to Uchiyomi.** Chapters you read in Mihon are marked read in Mihon
only. This is not a missing feature of the extension — an extension has no way to report it. In the Mihon
family, "chapter read → server" is a *tracker* compiled into the app itself and bound to a source by class
name; the extension API offers no hook. If that ever changes upstream, this extension will use it. Until
then, the extension never writes anything, which is also why a read-only token is enough.

## How it is built

- Kotlin, compiled against `tachiyomix` 1.6 (the extension API), with the suspend API as the implementation
  and the legacy request/parse pairs delegating to it, so it runs on hosts of either generation.
- Source id **`8683375824843625513`** — pinned in `build.gradle.kts`, asserted by a test, checked by CI.
  A library is keyed on it; it must never change.
- Two artefacts per release: the APK, and a JVM jar of the same classes for Suwayomi (its JDK refuses what
  dex2jar makes of an optimised APK, so the store's `jarUrl` points at a jar built from the compiled classes
  directly).
- CI signs with a release key that exists only in GitHub secrets, refuses to publish a debug-signed build,
  and force-pushes the generated store to the `repo` branch on every push to `main`.

### Build it yourself

```bash
./gradlew testDebugUnitTest assembleRelease createReleaseExtensionJar
```

Without `signingkey.jks` the APK is signed with the debug key — fine for a local install, never for the
store. The tests decode captured responses from a real Uchiyomi (`src/test/resources`) through the DTOs,
and pin the mapping decisions: status strings, chapter order, the never-empty-chapter rule, the id.

### Verify against a real host

The end-to-end check that ran before the first release used Suwayomi-Server (v2.3.2243, lib-1.6 support):
install the jar, set the two preferences through its API, then popular → latest → search → details →
chapters → pages → an image. It is the same engine Tachimanga descends from and needs no phone.

## Licence

[MPL-2.0](LICENSE), like Uchiyomi.
