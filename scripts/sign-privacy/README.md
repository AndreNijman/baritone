# Baritone 1.20.0: sign privacy, movement and autonomous pickaxe crafting

Target: **Minecraft 26.3, Fabric Loader 0.19.5 or later, Java 25 or later**.

The candidate includes the sign privacy patch and the Minecraft 26.3 keyboard
input and mining corrections described in `docs/anticheat-compatibility.md`.

The installable JAR is `dist/baritone-api-fabric-1.20.0-compat.7.jar`.
Replace the existing Baritone JAR in the instance's `mods` directory with this file.
Restart Minecraft, and run `#help` to confirm Baritone loads. Only one Baritone JAR
should be installed. This is the API release with its normal commands and bundled
nether pathfinder. Eight original classes are changed: input, mining, core initialization, look behavior,
both aim processors, geometric reachability and its API. Ten classes are added.
Every other original class and nested JAR is preserved byte for byte.

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
installed mods can still resolve their own probe keys. The movement correction
normalizes diagonal input and leaves sneak slowdown to Minecraft, matching its
keyboard input path. Mining now sends Minecraft 26.3’s normal `Punch` packet
after each mining swing and uses the held item’s attack animation. The game
controller still determines mining progress and block destruction. Other
anti-cheat checks have not been fully verified. Gradual ground aiming applies
12-degree yaw and 8-degree pitch caps per tick before normal rotation transmission.
It eases toward the target using actual mouse-sensitivity increments. Mining waits
until the current view ray matches the intended block. This bounds abrupt motion;
it does not establish human-like behavior against observers or classifiers.

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
- Passed 123 assertions using real Minecraft components and the actual candidate.
- Compared the candidate's optimized movement class with real Minecraft
  KeyboardInput/Vec2/Input for all 128 key combinations; all match. The source
  regression check also passes all 128 combinations.
- Applied the candidate through cached Fabric/Knot and Mixin 0.8.7; the actual sign
  constructor contains exactly one redirect and no unredirected conversion.
- Executed the real transformed editor constructor/removal and sign-update packet
  codec for 100 cases covering both sides, distinct filtered/unfiltered text, and
  changing fallbacks. All return the supplied fallback and normal keybind control.
- Repeated all 100 cases with the official unpatched release: the local translation
  appears in the outgoing packet, establishing a positive control.
- Compiled the changed BlockBreakHelper source against the real Minecraft 26.3
  and Baritone API classes.
- Initialized the real Baritone API, core pathing/input processes, and the
  help/goto/stop/mine/build/follow command registrations in the headless runtime.
- Compiled the included mixin source against the real game and Mixin API.
- Checked archive integrity: eight original classes and two metadata files change.
  Ten classes are added; bundled libraries are unchanged.
- Passed 142,626 geometry and steering assertions covering wraparound, angle caps, pitch limits,
  mouse-sensitivity increments, convergence and disabling the controller.
- Compiled the changed reachability API independently of the new main implementation.
- Tested pure live peeks, isolated fork progression, and an unlimited geometric
  endpoint in the rendered game. Reachability after turning is distinct from
  permission to break at the current angle.

Compilation and fixture execution used an offline bubblewrap sandbox with a
read-only source/toolchain, empty environment, scratch-local home/cache/tmp,
128 MiB writable tmpfs, 1 GiB memory limit, 64-task limit, two-CPU quota, 90-second
CPU limit, 120-second wall timeout, 32 MiB per-file limit, and 256 open-file limit.

The real-runtime checks use verified cached game libraries, a 256 MiB scratch
tmpfs, 2 GiB memory limit, 96-task limit, two-CPU quota, 90-second CPU limit,
120-second wall timeout, 32 MiB per-file limit, and 256 open-file limit. They create
minimal client control objects without graphics or an account, and capture
outgoing packets before transport. They do not contact a server.

**The complete component/input/Mixin/packet-codec suite also passes with official
Fabric 0.19.5 and no dependency override. The earlier 0.19.3 cache checks used a
scratch-only override; the artifact has always required 0.19.5+. Full game and
server gameplay validation is tracked separately in the clean-room benchmark
project. These unit/runtime checks do not establish a complete anti-cheat bypass.**

## Mining gameplay checks (compat.2)

A rendered client joined a disposable Paper 26.3 build 140 server with public
Grim 2.3.74-61117c2 and experimental checks enabled. All three profiles mined nine
of nine stone blocks, with nine server-confirmed air states, nine cobblestone
collected, 63 normal Punch packets and no NoSwingBreak flags. The old build sends
zero Punch packets and triggers 18 NoSwingBreak flags in the comparison.

An explicit synthetic server rule requiring a recent Punch reproduces 32 block
restorations with the old build. Compat.2 has zero restorations and mines all
nine. A normal attack-key control also removes all nine with zero restorations
or flags. This is a test rule, not a claim about the private server. Startup timer
flags remain in some ordinary profiles; the conservative trial also has a startup
BadPacketsR flag. Full details are in `docs/anticheat-compatibility.md` and the
separate clean-room report. The private server's exact rejection remains unknown.

## Persistent realistic movement (compat.7)

The controller defaults on. `#realistic` toggles it; `#realistic on`,
`#realistic off` and `#realistic status` set or inspect it. The `#gradual` alias
remains available (without arguments, it reports status). The choice is saved in
`baritone/realistic-movement.properties` and survives restart. Enabling applies:

```text
freeLook false
blockFreeLook false
smoothLook false
walkWhileBreaking false
```

This keeps the camera aligned with transmitted rotations and makes breaking
stationary. Sprint and parkour settings are preserved; the mode does not change
either setting. The installed profile enables both per the requested preference.
Turning the controller off restores the four settings from before activation,
provided they still have the preset value. Deliberate changes made while enabled
are preserved. It also disables eased turns, heading correction and the
matching-view mining gate. Sign privacy and normal 26.3 mining/input fixes remain.
Elytra flight retains its ordinary processor behavior. Other settings can still
be changed normally, so overriding these presets changes the tested behavior.

Both live and forked aim predictors use the bounded next-tick step. The live
predictor holds the pre-turn rotation for the whole tick, preventing movement
calculations from accidentally taking a second step. Geometric reachability
uses a separate pure endpoint so a reachable block is not discarded merely
because turning takes several ticks. Breaking uses the current view ray and
normal controller progression/abort packets.

Travel steering now chooses the nearest normal keyboard direction relative to
that tick's eased view, so forward travel does not keep following the previous
heading while the camera turns. The original 0.35 easing curve and angle caps
are unchanged. This applies to current-tick travel targets on the ground and in water.
Dry-land jumps and airborne movement, interactions, vehicles and flight keep
their existing input behavior. Direction choices use normal key states and normalized vectors.
Travel target state clears on each tick, world change and mode toggle.

In water, travel steering also runs while floating and while jump is held for
buoyancy. Water at the current movement’s source or destination keeps steering
active through surface bobbing and bank exits. The jump key remains untouched.
Dry movements retain the previous jump and airborne behavior, as do interactions,
vehicles and elytra. Rotation easing, sprint and parkour settings stay unchanged.
This does not add fully submerged path planning.

The clean-room gameplay report is
`../andre-anticheat/reports/2026-10-01-water.md` relative to the project root.
It records received rotation measurements, water crossings, dry-land regressions,
unsuccessful controls, the submerged planning limitation and remaining flags. There is no human-observer study and no
proof against the undisclosed private stack.

## Autonomous diamond pickaxe

Run `#diamondpickaxe` in a normal survival world. It reuses supplies you already
have, and otherwise reserves four logs before leaving the first tree, crafts planks,
sticks and a crafting table,
crafts wooden and stone pickaxes, mines raw iron and furnace cobblestone, places
a furnace, smelts iron using available coal/charcoal or gathered planks, crafts an
iron pickaxe, mines three diamonds and crafts a diamond pickaxe. It uses normal
survival interactions and inventory clicks; no recipe-book unlock is needed.

Use `#diamondpickaxe status` to inspect the current stage, and
`#diamondpickaxe stop` or `#stop` to cancel. Start with a clear crafting cursor
and a closed container. The task temporarily enables mining/placement and
disables competing inventory management, then restores unchanged settings on
completion or cancellation. Sprint, parkour and the realistic toggle are retained.
Placed stations remain in the world for reuse by the player.

Resources must exist and be reachable in the world. Exploration uses upstream
Baritone mining; it does not add submerged path planning, food, combat or
dimension travel. Death, disconnect, a full inventory, repeated mining failure
or a sustained crafting/smelting stall stops the task with a message. A server
that rejects the ordinary actions can prevent completion. Restarting the command
reuses surviving materials and tools; a mission is not resumed across reconnects.

The exact compat.7 artifact completed the empty-inventory chain with realism both
on and off, including every server-confirmed recipe and three iron smelts.
An equipped-inventory trial reused an iron pickaxe and skipped earlier tiers.
Cancellation with four logs on the cursor returned them to inventory and restored
three deliberately different starting settings. Saved-off startup and the bare
`#realistic` toggle were exercised in the rendered game. Full results, failed
prototypes and remaining flags are in
`../andre-anticheat/reports/2026-10-06-diamond.md` from the project root.

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

These server results are expectations supported by the local runtime tests. Keep your
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
  --jdk /usr/lib/jvm/java-25-openjdk \
  --metadata /path/to/26.3.json \
  --libraries /path/to/launcher/meta/libraries
```

The host needs Python 3, bubblewrap, util-linux `prlimit`, and a user systemd
session supporting the resource controls above. There is no network access in
the compiler/test sandbox. The normal Gradle source also contains the same
helper and mixin registration; a complete Gradle rebuild was not performed.

Run the real-runtime tests with already cached Linux launcher libraries:

```sh
python3 scripts/compatibility/test_runtime.py \
  --minecraft /path/to/minecraft-26.3.jar \
  --metadata /path/to/26.3.json \
  --candidate dist/baritone-api-fabric-1.20.0-compat.7.jar \
  --libraries /path/to/launcher/meta/libraries \
  --fabric-runtime \
  --upstream-control /path/to/baritone-api-fabric-1.20.0.jar
```

The optional Fabric checks pin Mixin 0.8.7 and ASM 9.10.1 hashes. Supply
`--fabric-loader /path/to/fabric-loader-0.19.5.jar` to use the verified supported
loader without an override. Without this argument they use the cached 0.19.3
loader with an explicit scratch-only override. They never alter your launcher
profile or fetch dependencies during execution.
Without `--fabric-runtime`, only the actual-component and actual-input checks run.

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
