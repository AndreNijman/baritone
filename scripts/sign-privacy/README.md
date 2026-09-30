# Baritone 1.20.0: sign translation privacy patch

Target: **Minecraft 26.3, Fabric Loader 0.19.5 or later, Java 25 or later**.

The installable JAR is `dist/baritone-api-fabric-1.20.0-signprivacy.1.jar`.
Replace the existing Baritone JAR in the instance's `mods` directory with this file.
Restart Minecraft, and run `#help` to confirm Baritone loads. Only one Baritone JAR
should be installed. This is the API release with its normal commands and bundled
nether pathfinder; every original class and nested JAR is preserved byte for byte.

## What the patch does

The sign editor's conversion of incoming components into editable strings treats
translation keys beginning with `baritone.` as unknown. It uses the **actual
incoming fallback**, or the key itself when the component has no fallback.
Translation arguments and sibling components are handled recursively, including
components whose normal rendering already cached a translated value. Local
translations are available elsewhere. Other translations, vanilla keybind
resolution (including the `key.forward` control), and the sign update packet path
retain their ordinary behavior.

This is scoped to the sign translation probe described in the request. Other
installed mods can still resolve their own probe keys. Movement checks and other
anti-cheat mechanisms are outside this patch's scope.

An important correction to the supplied explanation: the official Baritone
v1.20.0 Fabric JAR has **no `assets/*/lang/*` files**, and its source does not
register `baritone.setting.allowBreak`. Consequently, the public CheckHacks
Baritone probe should already return its fallback for that official release.
The patch also covers the case where a resource pack or another mod contributes
a Baritone translation. A sign lookup establishes the presence of a translation
resource, which alone is not proof that the corresponding mod is installed.

## Validation performed

- Verified the official release SHA-256 against GitHub's published digest.
- Verified the Minecraft 26.3 client SHA-1 against Mojang's version manifest.
- Checked all nine public Minecraft methods/constructor used by the helper in
  the actual 26.3 bytecode, plus exactly one conversion call at the constructor
  injection point.
- Compiled the helper and generated a redirect mixin equivalent to the included
  `MixinSignEditScreen.java`, targeting Java 25. The overlay route avoids running
  upstream Gradle scripts or downloading build dependencies. ASM 9.9 is an
  already installed build tool; it is not included in the mod.
- Passed 38 assertions against a small component fixture, including a populated
  Baritone translation table, cached results, nesting, custom fallbacks, ordinary
  translations, a custom forward-key value, and translation placeholders.
- Checked archive integrity and verified that the only changed upstream files
  are Fabric metadata and the mixin configuration. Four compiled classes are added.

Compilation and fixture execution used an offline bubblewrap sandbox with a
read-only source/toolchain, empty environment, scratch-local home/cache/tmp,
128 MiB writable tmpfs, 1 GiB memory limit, 64-task limit, two-CPU quota, 90-second
CPU limit, 120-second wall timeout, 32 MiB per-file limit, and 128 open-file limit.

**Minecraft launch, runtime Mixin application, pathfinding, and an actual
CheckHacks packet round trip have not been tested. This is a built candidate,
not a claim of a verified complete anti-cheat bypass.**

## Validate on your server

Use a local/test instance and check the Baritone definition explicitly:

```text
/checkhacks YourPlayerName baritone
```

1. Test official Baritone without the test resource pack. The expected result
   for this one probe is `NOT_DETECTED`, because the official JAR lacks the key.
2. Install `dist/baritone-probe-test-pack-26.3.zip` in the client's `resourcepacks`
   directory, enable it, and select English (US). It contributes only
   `baritone.setting.allowBreak = "BARITONE_PROBE_TEST_TRANSLATION"`.
3. With official Baritone and the test pack enabled, repeat the scan. The expected
   result is `DETECTED`. This is the positive control for your test setup.
4. Replace official Baritone with the patched JAR, leaving the same test pack
   enabled. Restart and repeat the scan. The expected result is `NOT_DETECTED`,
   with the returned fallback `⟦NO_BARITONE⟧`, and a normally resolved fourth line.
   Repeat the scan to check the confirmation/timing path; also test a normal sign.
5. Check `#help`, run `#goto` to a nearby safe coordinate in your test world, and
   use `#stop`. This checks that Baritone still operates in your installed setup.

These are expected results from source inspection and fixture tests. Keep your
observed server log/results when doing the in-game validation. The test pack is
deliberately detectable and should be removed after this comparison.

## Rebuild offline

The input JARs are pinned; the builder rejects different versions or hashes.
Given the existing ASM 9.9 JAR and a JDK under `/usr`, run:

```sh
python3 scripts/sign-privacy/build_overlay.py \
  --upstream /path/to/baritone-api-fabric-1.20.0.jar \
  --minecraft /path/to/minecraft-26.3.jar \
  --asm /path/to/asm-9.9.jar \
  --jdk /usr/lib/jvm/java-27
```

The host needs Python 3, bubblewrap, util-linux `prlimit`, and a user systemd
session supporting the resource controls above. There is no network access in
the compiler/test sandbox. The normal Gradle source also contains the same
helper and mixin registration; a complete Gradle rebuild was not performed.

The builder also generates the positive-control test resource pack, installation
guide, and complete source archive. The source archive contains Baritone v1.20.0
plus these changes. Generated artifacts remain local in `dist/`; source changes
are maintained on the `fix/mc265322-sign-privacy` feature branch.

## Sources

- [Official Baritone v1.20.0 release](https://github.com/cabaletta/baritone/releases/tag/v1.20.0)
- [Pinned upstream source](https://github.com/cabaletta/baritone/tree/25111dae)
- [CheckHacks detector](https://github.com/branduzzo/CheckHacks/blob/main/src/main/java/me/branduzzo/checkHacks/managers/CheckManager.java)
- [CheckHacks probe definitions](https://github.com/branduzzo/CheckHacks/blob/main/src/main/resources/checkhacks.yml)
- [Mojang version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
- [Mojang pack metadata format](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-9)
