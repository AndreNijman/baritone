# Baritone 1.20.0: sign privacy, movement, autonomous pickaxe crafting and mob avoidance

Target: **Minecraft 26.3, Fabric Loader 0.19.5 or later, Java 25 or later**.

The candidate includes the sign privacy patch and the Minecraft 26.3 keyboard
input and mining corrections described in `docs/anticheat-compatibility.md`.

The installable JAR is `dist/baritone-api-fabric-1.20.0-compat.9.jar`.
Replace the existing Baritone JAR in the instance's `mods` directory with this file.
Restart Minecraft, and run `#help` to confirm Baritone loads. Only one Baritone JAR
should be installed. This is the API release with its normal commands and bundled
nether pathfinder. Twelve original classes are changed: input, mining, core initialization, look behavior,
both aim processors, geometric reachability and its API, mob avoidance, water passability, movement and
builder placement checks. Nineteen classes are added.
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
- Checked archive integrity: twelve original classes and two metadata files change.
  Nineteen classes are added; bundled libraries are unchanged.
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

## Numpad 9 stop (compat.8)

Press **Numpad 9** to execute the normal `#stop` command. It cancels mining,
pathing and the diamond-pickaxe task, including while its crafting screen is open.
The top-row 9 is unaffected. Num Lock does not change the binding; holding the
key does not repeatedly invoke stop. The binding is active only in a world and
consumes that keypad key before ordinary screen handling.

## Hostile mob avoidance (compat.9)

While any Baritone task runs (`#diamondpickaxe`, `#mine`, `#goto` and the rest),
it now stays away from hostile mobs. `#avoidmobs` toggles this; `#avoidmobs on`,
`#avoidmobs off` and `#avoidmobs status` set or inspect it. It defaults on and the
choice is saved in `baritone/mob-safety.properties`. Manual play is never taken
over: nothing happens unless a Baritone process is in control.

- **Planning.** Paths cost four times as much near hostile mobs: within 8 blocks
  of ordinary mobs, 10 of creepers, spiders and fast melee mobs, 14 of skeletons
  and other ranged mobs, and 20 of wardens (Baritone's spawner avoidance also
  applies). Upstream's filter used `instanceof Mob`, which under Mojang names
  also matches cows, villagers and golems; it now requires a hostile (`Enemy`)
  mob, so passive animals are ignored. Enabling sets `avoidance true` and
  `mobAvoidanceCoefficient 4.0`; turning it off restores the previous values
  unless you changed them meanwhile.
- **Retreat.** The running task is paused, not cancelled, when a hostile comes
  within its trigger distance:

  | Mob | With line of sight | Without | Runs to |
  | --- | ---: | ---: | ---: |
  | Zombies, slimes, silverfish and other melee | 6 | 3 | 16 |
  | Spiders, cave spiders, vindicators, hoglins, zoglins, ravagers, angry piglins/endermen | 8 | 4 | 18 |
  | Skeletons/strays/bogged with a bow, pillagers, witches, blazes, breezes, guardians, shulkers, evokers | 16 | 4 | 24, or any nearby spot they cannot see |
  | Creepers (9 once swelling, with or without sight) | 8 | 5 | 16 |
  | Wardens | 20 | 20 | 30 |

  Melee mobs more than 4 blocks above or below (8 for spiders) are ignored, since
  they cannot reach. Against ranged mobs, standing spots within 8 blocks whose head
  position they cannot see count as safe, so the bot ducks behind cover rather than
  running in the open. The bot paths away through ordinary movement, so realistic
  aiming, sprint and parkour still apply. An open crafting table or furnace is
  closed normally first. The escape route is kept while its end stays safe from
  where the mobs are now, so the bot does not stop to replan every second. Once
  nothing qualifies within those distances plus six blocks for one second, the task
  resumes and replans from where it stands. Calm neutral mobs, no-AI mobs and flying
  mobs (phantoms, ghasts, vexes) do not trigger a retreat.
- **Bounds.** A retreat that lasts 30 seconds, or finds no escape path three
  times, hands control back to the task for five seconds (only a swelling creeper
  or warden interrupts that pause). `#stop` and Numpad 9 cancel a retreat like any
  other task.

It never attacks. A melee mob that keeps chasing causes repeated retreats rather
than progress, and in tight caves or dead ends there may be nowhere to go.
Arrows already in flight are not dodged. Food, totems and combat are still not
handled.

## Shallow flowing water (compat.9)

Upstream Baritone refuses to walk through flowing water, and also treats still
water next to any flowing block as flowing. A lake spilling one block deep into a
two-high tunnel is therefore a wall to it: the bot treads water at the mouth and
never enters. Compat.9 allows a horizontally flowing water layer with open space
above and a floor or more water below. Falling water, fully submerged water, water
over a drop and all lava keep the upstream rule.

## Placement with realistic aiming and a carried crafting table (compat.9)

Baritone checks whether it can place a block (bridging, stepping up onto a placed
block, climbing out of holes, `#build`) by ray-casting along the aim it will have.
With realistic aiming that check used only the next bounded turn step, so a face
more than one step away never qualified: the look target was never set and the bot
retried until the player aimed for it. The checks now use the aim's eventual
endpoint; the actual right click still waits until the crosshair is on the face.

`#diamondpickaxe` now breaks its crafting table after each use with normal aimed
mining, collects the drop and places it again at the next work site, instead of
crafting a new table wherever it is. If the pickup fails within 20 seconds it
continues and crafts another table when needed.

## Realistic jumps and server-undone stations (compat.9)

With realistic movement on, parkour jumps, step-ups and pillars pressed jump and
forward the moment they started, while the eased camera was still turning from the
previous direction; the jump left the wrong way, missed, and the path was retried
from another side. These movements now wait on their starting block until the aim
faces the jump (within 5 degrees for parkour, 10 for step-ups, looking down for a
pillar), for at most one second, and never stop once under way.

`#diamondpickaxe` treats a crafting table or furnace that vanishes within ten
seconds of being placed as undone by the server (a ghost block): it waits two
seconds for the inventory to resync before counting items, avoids that spot, and
stops with a message after three such rejections. It crafts at most three tables
per run instead of looping. Station spots may replace grass, ferns and snow layers,
may be one block higher or lower, and when none is visible the bot walks a few
blocks and looks again (three times) instead of stopping.

Keep realistic movement on: with it off, rotations snap instantly and transmitted
rotations need not match the camera, which servers can reject. In real use with it
off, placed crafting tables were removed by the server within about a second.
`#diamondpickaxe` therefore switches realistic aiming on for the duration of the
task when it is off, and restores your setting (without saving) when the task ends.
Station placement and use also wait until the eased aim has settled within two
degrees of the target before clicking.

## Respawn and continue; dig down for stone (compat.9)

When the player dies during `#diamondpickaxe`, the task presses respawn (after the
death screen's usual delay), waits five seconds for the server's respawn teleport
and chunks to settle, then restarts from the current inventory. Three deaths within
five minutes stop it instead. For cobblestone it digs straight down to the nearest
stone within twelve blocks below (preferring directly underneath) rather than
walking to distant exposed stone, falling back to ordinary mining if there is none,
planning fails three times, or no cobblestone arrives for a minute.

### What was tested (compat.9)

Rendered clean-room trials on the disposable Paper/Grim server (report:
`../andre-anticheat/reports/2026-10-06-mobs.md`), compat.8 as the control:

- Motionless zombie on a straight 20-block route: compat.8 passed 0.10 blocks
  from it; compat.9 kept 8.25 blocks away and arrived. A cow in the same spot is
  ignored (same straight path as compat.8).
- `#diamondpickaxe` with a hunting zombie: compat.8 took 8 hits and died; compat.9
  took no damage over five retreats (closest 5.1 blocks).
- `#diamondpickaxe` with a creeper: compat.8 was blown up and never finished;
  compat.9 took no damage, no explosion, and completed the diamond pickaxe.
- Lake spilling flowing water into a two-high tunnel: compat.8 trod water at the
  mouth until timeout; the final compat.9 entered, waded through and arrived.

Those mob trials ran on earlier compat.9 candidates; the final JAR was only
trialled on the flowing-water tunnel and the headless runtime suite was last
run on an intermediate candidate. The skeleton fixture, the line-of-sight hiding
and the retreat route-keeping changes have no rendered trial yet.
The placement-aim fix, crafting-table pickup, jump alignment and ghost-station
handling were checked by compilation and the headless runtime suite; the existing
straight parkour route still arrives in 50 ticks (48 sprinting, 11 airborne), as in
compat.7/8, so the jump hold does not stall an aligned jump. Real-world testing is still needed.

## Autonomous diamond pickaxe

Run `#diamondpickaxe` in a normal survival world. It reuses supplies you already
have, and otherwise reserves four logs before leaving the first tree, crafts planks,
sticks and a crafting table,
crafts wooden and stone pickaxes, mines raw iron and furnace cobblestone, places
a furnace, smelts iron using available coal/charcoal or gathered planks, crafts an
iron pickaxe, mines three diamonds and crafts a diamond pickaxe. It uses normal
survival interactions and inventory clicks; no recipe-book unlock is needed.

The task collects eight dirt blocks for scaffolding and replenishes between stages
when fewer than four disposable blocks remain. It keeps dirt or netherrack in the
hotbar for bridging and climbing. During this task, only those two items are
acceptable pathing throwaways, so cobblestone, stone and other crafting ingredients
are retained for recipes. Your original throwaway list is restored on completion
or cancellation unless you deliberately changed it during the task. A route still
requires accessible resources and an escape that Baritone can plan.

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
  --candidate dist/baritone-api-fabric-1.20.0-compat.9.jar \
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
