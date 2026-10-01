# Anti-cheat compatibility work — 2026-09-30

Andre authorized committing/pushing the sign privacy patch and starting further
work against the anti-cheats on his own server. The active test scope is now blind and clean-room: do not inspect or request
Andre's private server, plugin list, or configurations. Published Paper, Grim,
and CheckHacks builds are independent experimental opponents. DonutSMP
production parity has not been established; no universal bypass is claimed.

## Saved state

- Worktree: `/var/home/andre/Projects/baritone-sign-privacy`.
- Remote: `https://github.com/AndreNijman/baritone.git`.
- Branch: `fix/mc265322-sign-privacy`.
- Base: official Baritone v1.20.0, upstream commit `25111dae`, Minecraft 26.3.
- Sign patch committed and pushed as `8d3d30a7`.
- Built sign-only candidate: `dist/baritone-api-fabric-1.20.0-signprivacy.1.jar`.
- Candidate SHA-256: `9698ef0c506cdc715d10cc97bc11cd5a4685f44d163dd52bb18e659871e49840`.
- Combined sign/input candidate: `dist/baritone-api-fabric-1.20.0-compat.1.jar`.
- Combined SHA-256: `b9fc3e716cfb8145682e601b38e5b94a2a7cb899227416a42b3a74cb43571955`.
- Movement source fix committed/pushed as `b18a8d76`.
- Source, positive-control pack, and test guide are preserved in the repository
  and generated locally. Generated binaries are ignored by Git.
- Memory boot intermittently fails; a decision checkpoint was successfully saved
  to the NAS after runtime validation. This repository also preserves the handoff.

## First concrete compatibility fix

`PlayerMovementInput.tick()` differed from Minecraft 26.3's `KeyboardInput.tick()`:

1. Forward + left produced `(1, 1)` in Baritone, while vanilla normalizes it to
   approximately `(0.70710677, 0.70710677)`.
2. Baritone multiplied the vector by 0.3 when sneaking. Vanilla keyboard input
   leaves the vector unscaled; `LocalPlayer.modifyInput()` later applies the
   `SNEAKING_SPEED` attribute. The Baritone scaling therefore adds an extra slowdown.

The source fix normalizes the vector and leaves sneak slowdown to LocalPlayer.
The seven reported key states (including jump, sneak, and sprint) are unchanged.
This aligns the input with vanilla rather than manufacturing movement packets.

The regression test executes **actual Minecraft 26.3 KeyboardInput bytecode**
against the production Baritone input class for all 128 combinations of its
seven boolean inputs. Narrow fixtures provide Options/KeyMapping, the input
record, ClientInput fields, vector normalization, and the override handler. The
fixture math/API surfaces are not a full Minecraft runtime.

- The pinned upstream class fails the differential test: input mask 5 produces
  the unnormalized `(1, 1)` vector.
- The fixed source passes all 128 combinations, including sneak and diagonal inputs.
- Compilation and execution run in the same resource-limited offline sandbox
  used for the sign patch. No target Gradle scripts or downloaded dependencies execute.

Run the regression check with:

```sh
python3 scripts/compatibility/test_input.py --minecraft /path/to/minecraft-26.3.jar
```

The combined `compat.1` overlay now includes the same fix in the optimized release
class (`baritone/fp.class`); its actual methods match the real game input/vector
classes in all 128 combinations, without those earlier API/math fixtures. The
pinned input JAR and the class's structure are checked before replacing `tick()`.
An in-game pathfinding/physics comparison remains unverified. No measured
anti-cheat flag reduction is claimed.

## Further source review

The public Grim source was inspected at commit
`61117c2865f603f4990df09fdaa4adeaf7d8a557`. Its modern input transformer normalizes
keyboard vectors and applies sneak slowdown later, matching the vanilla path.
That is useful independent evidence for the input correction. It does not
establish the behavior of a private fork or a different installed version.

| Surface | Evidence inspected | Next validation |
| --- | --- | --- |
| Sign translation | Real component assertions; Fabric/Mixin constructor redirect; patched/unpatched editor and packet-codec round trips | Actual CheckHacks scans |
| Movement input | Fixed source and optimized release class; actual 26.3 KeyboardInput/Vec2/Input; Grim source | Compare walking/diagonal/sneak paths in game |
| Rotations | LookBehavior applies targets before packet updates; smoothLook runs in POST | Correlate server rotation/placement flags with packet order |
| Placement | BlockPlaceHelper invokes the game controller; Grim RotationPlace/MultiPlace validate interaction state | Reproduce any placement flags with actual logs and configuration |
| Mining | BlockBreakHelper uses controller progress/delay; Grim FastBreak checks progress/timing | Reproduce observed mining flags without assuming thresholds |
| Inventory | allowInventory defaults false; optional stationary gating exists | Test only the inventory behavior enabled on this client/server |
| Protocol/versions | Client 26.3 uses modern key-state reporting; current Grim source contains 26.3 cases | Exercise the chosen direct Paper backend; proxy coverage remains separate |

`antiCheatCompatibility` already defaults to true. Its name is not a guarantee
that every check or private server modification is covered. `smoothLook` changes
the local camera after rotation packets; enabling it alone does not smooth the
rotations observed by the server.

## Clean-room experiments

Use fresh disposable worlds, public versioned plugins, explicit configuration
profiles, and a synthetic local test account. Preserve the original client as a
positive control and enable a test-only language pack so the translation oracle
actually distinguishes stock from patched behavior. Never infer compatibility
with the undisclosed private stack from this benchmark.

## Sources

- [Baritone movement input at the reviewed base](https://github.com/cabaletta/baritone/blob/25111dae/src/main/java/baritone/utils/PlayerMovementInput.java)
- [Baritone look behavior](https://github.com/cabaletta/baritone/blob/25111dae/src/main/java/baritone/behavior/LookBehavior.java)
- [Baritone settings](https://github.com/cabaletta/baritone/blob/25111dae/src/api/java/baritone/api/Settings.java)
- [Pinned Grim modern input transformer](https://github.com/GrimAnticheat/Grim/blob/61117c2865f603f4990df09fdaa4adeaf7d8a557/common/src/main/java/ac/grim/grimac/predictionengine/predictions/input/impl/ModernInputTransformer.java)
- [Pinned Grim RotationPlace](https://github.com/GrimAnticheat/Grim/blob/61117c2865f603f4990df09fdaa4adeaf7d8a557/common/src/main/java/ac/grim/grimac/checks/impl/scaffolding/RotationPlace.java)
- [Pinned Grim FastBreak](https://github.com/GrimAnticheat/Grim/blob/61117c2865f603f4990df09fdaa4adeaf7d8a557/common/src/main/java/ac/grim/grimac/checks/impl/breaking/FastBreak.java)

## Autonomous runtime verification — 2026-09-30

Cached Minecraft 26.3/Linux libraries enabled stronger local checks, without any
network or changes to existing launcher profiles:

- 123 assertions on real Minecraft components, including cached/nested values,
  fallback formatting, ordinary translations, and custom forward bindings.
- All 128 key combinations in the actual candidate input class match real
  KeyboardInput/Vec2/Input.
- Fabric/Knot and Mixin 0.8.7 apply exactly one redirect to the real constructor.
- 100 patched editor constructor/removal/sign-packet-codec cases return dynamic
  fallbacks; 100 unpatched positive-control cases return the installed translation.
  Both sides and distinct filtered/unfiltered inputs are exercised. Packet sends
  are captured locally; no Paper plugin or server is involved.
- The real Baritone API initializes its core pathing/input processes and registers
  help/goto/stop/mine/build/follow in the minimal headless client context. This is
  initialization coverage, not in-world pathfinding or command execution.
- The included normal mixin source compiles against the real game/Mixin API.

The cache contains loader 0.19.3, below the upstream minimum 0.19.5. These Fabric
checks use a scratch-only dependency override. The artifact still requires
0.19.5+; this does not establish compatibility on the supported loader. Headless
control objects use test-only allocation/reflection, and no rendered game/world
session or complete CheckHacks sequence has been tested.

Reproduction commands and resource limits: `scripts/sign-privacy/README.md`.
Runtime log: `.validation/actual-runtime.log` (ignored, local). A NAS decision checkpoint was saved after the initial boot error; this document
also preserves the project handoff.


## Supported loader and clean-room constraint

The complete suite was rerun with the official Fabric 0.19.5 JAR (SHA-256
`93044e4dd46de5d8136701292f05e868da096d2c9fddb4793e4fdbcc63efc695`), without a
dependency override: real components, all 128 inputs, real Mixin application,
Baritone initialization, and 100 patched plus 100 unpatched packet-codec cases pass.
Local log: `.validation/actual-runtime-0195.log`.

Andre subsequently requested blind clean-room tests. Do not inspect or request his
private server or installed anti-cheats. The separate
`/var/home/andre/Projects/andre-anticheat` project uses published Paper 26.3 build
140, Grim 2.3.74-61117c2, CheckHacks 1.3.1, and new explicit configurations. These
opponents are experimental choices, not claims about his server's hidden stack.

## Rendered client benchmark

A fresh Minecraft 26.3/Fabric 0.19.5 client now joins the disposable Paper server,
with the positive-control translation pack enabled, and completes a normal
diagonal Baritone goal. Both stock and patched builds complete the conservative,
balanced, and strict profile paths. The primary strict patched trial emits Timer
and TimerLimit flags after joining, before its goal starts; the other five normal
primary trials emit no flags. No measured movement flag reduction is claimed.

Public CheckHacks 1.3.1 initially returns `PROTECTED` for both variants because its
reflection code still requests the removed `(BlockPos, boolean)` open-sign packet
constructor. The test overlay changes only that adapter to support
`(BlockPos, SignTextSlot.FRONT)`. Every other upstream class, including response
evaluation, confirmation, timing, and fallback construction, is byte-identical.
With that documented server compatibility overlay, the patched client returns
`NOT_DETECTED`; stock with the same test pack returns `DETECTED` on the initial
and confirmation scan. This is an actual client/server sign interaction, in
addition to the earlier constructor/codec tests.

The clean-room project records all selected profile comparisons and deliberate
invalid-movement controls. Its report is authoritative for those bounded trials.
This result does not establish universal anti-cheat evasion, ordinary gameplay
coverage beyond the tested path, or behavior of an undisclosed server stack.


## Minecraft 26.3 mining protocol correction

Andre reported that Baritone-mined blocks briefly become air, then return, and
Baritone walks into the restored collision. Manual mining succeeds. This is
consistent with client prediction followed by a server correction; the private
server and its rejection reason remain unknown.

The actual 26.3 `Minecraft.startAttack` and `continueAttack` bytecode both perform
local swing animation and explicitly send `ServerboundPunchPacket.INSTANCE`.
Baritone v1.20.0 calls `LocalPlayer.swing(..., false)` but never sends that Punch.
The false argument means this local animation does not provide a replacement
notification. The source helper now sends the normal Punch after each mining
swing and uses the held item's attack animation. Mining speed, tool selection,
controller progression and destruction acknowledgements are unchanged.

The optimized release inlines the two mining branches into
`baritone/fj.onTick`, using the `baritone/fd` helper in local variable 1.
`PatchMining.java` patches only those two branches; its third, right-click swing
is preserved. The builder verifies the pinned upstream hash and expected method
structure. The new `compat.2` JAR changes this optimized mining class in addition
to the existing input/sign changes. All nested libraries remain byte-identical.

The public Grim source at `61117c2865f603f4990df09fdaa4adeaf7d8a557` recognizes
26.3 PUNCH as an animation and its experimental `NoSwingBreak` flags break actions
without one. This check is explicitly enabled only for the mining benchmark.
It does not itself cancel mining in that public source. Consequently, finding
this mismatch does not prove it caused the private server's block correction.

The clean-room project has a separate disposable `MiningProbe` instrument that
counts received Punch packets, accepted BlockBreakEvents, block state one tick
later, remaining fixture stone, and actual cobblestone inventory. Its optional
synthetic gate cancels fixture breaks without a recent Punch, to reproduce
prediction/correction independently of the unknown server configuration. That
gate is a declared laboratory control, not a production anti-cheat or a claim
about Grim's default enforcement.

Full gameplay evidence and reproduction commands are recorded in
`/var/home/andre/Projects/andre-anticheat/reports/2026-09-30-mining.md`.


The new overlay SHA-256 is
`4db9797dd253a0cd8428201b64eec3d84e63c019ec627fc4a5bb3fa2a18fb108`.
The rendered ordinary mining trials remove all nine blocks in all three profiles,
with 63 Punch packets and nine cobblestone collected each, without NoSwingBreak.
The old comparison mines all nine under public Grim but emits 18 NoSwingBreak
entries and zero Punch packets. Under the explicit synthetic gate, the old build
has 32 rejected breaks/restorations and mines none; compat.2 mines all nine with
zero rejected breaks/restorations and no flags. The corrected normal attack-key
control also mines all nine, with zero restorations/flags and 103 Punch packets.
An earlier incomplete manual test used an incorrectly phased startAttack call;
it is retained in the report rather than counted as the normal input control.

Strict/conservative ordinary patched runs retain startup Timer/TimerLimit flags;
the conservative run also has BadPacketsR before mining starts. Their causes are
not resolved. The report preserves those findings. Components/input/Fabric/sign
codec regression checks still pass; the changed helper source also compiles
against the real game/API. A full Gradle rebuild remains unperformed.

Compat.2 was installed in the existing **Fabric 26.3** Modrinth profile after
checking no Java process was using it. The old verified compat.1 JAR is preserved
in that profile's `.codex-backups/mining-compat.2/`, outside `mods`. The mods folder
has one Baritone JAR. The Vanilla profile was not changed. Private server settings
were not inspected. NAS memory calls currently return internal errors; this
repository and the clean-room reports preserve the checkpoint.

## Gradual ground aiming — 2026-10-01 (compat.3)

The new ground controller limits transmitted yaw to 12 degrees and pitch to
8 degrees per tick, easing toward a target in actual mouse-sensitivity increments.
It uses the same pure next-tick step for live and forked processors. The live
processor snapshots the pre-turn view once per tick so later movement queries
cannot take a second turn step. Both settings and camera are aligned with those
transmitted rotations: freeLook, blockFreeLook, smoothLook, walkWhileBreaking,
allowSprint and allowParkour are set false when enabling the controller.

`#gradual on`, `#gradual off`, and `#gradual status` control this session-only mode.
It defaults on at restart. Turning it off leaves the current settings in place.
Users can override those presets through normal settings commands, which changes
the tested behavior. The rotation limiter bypasses Elytra flight.

Mining waits for the actual current view ray to hit the same block as the intended
interaction ray. Interrupted digging is aborted through the normal game controller.
Geometric reachability is a separate pure endpoint: a block can be reachable after
turning even if it cannot be targeted next tick. The API gains the backward-compatible
`IAimProcessor.peekRotationForReachability` default method, avoiding a dependency
from the API source set into main implementation classes.

The initial prototype exposed both a reachability regression (two of nine blocks
mined) and a SprintE flag while turning. It was not installed. Separating eventual
reachability from the next-tick predictor, freezing live prediction for the tick,
and disabling sprint/parkour resolved the tested baseline regression. The measured
strict baseline removes nine of nine blocks with no restorations and no logged
flags; server-received steps are at most 11.85 yaw and 7.95 pitch degrees at the
test sensitivity. Ordinary diagonal pathfinding also completes.

The final JAR's runtime suite passes 50,202 geometry assertions, 123 real-component
assertions, all 128 actual input combinations, supported Fabric 0.19.5 initialization,
and 100 patched plus 100 unpatched sign-editor/packet-codec round trips. Changed
reachability API sources compile against the original API and game without the new
main helper. A complete Gradle rebuild remains unperformed.

Broader gameplay cases and the actual artifact hashes are preserved in the separate
`/var/home/andre/Projects/andre-anticheat/reports/2026-10-01-gradual-look.md` report.
Some startup Timer/TimerLimit flags remain unresolved. Deterministic easing can itself
be recognizable. No human-observer study, classifier benchmark, proxy coverage,
placement/inventory coverage, or universal anti-cheat bypass is established.

Final compat.3 SHA-256:
`5ad28b14f2779bb5fc11397c824372f544517592b48054c8460d9a3a78c310b8`.
The final baseline, wooden-pickaxe, Mining Fatigue I and longer obsidian repeats
all remove nine blocks with zero restorations. The final normal diagonal goal
also arrives. Startup Timer/TimerLimit flags remain in the baseline/path repeats.
The installed compat.2 comparison has an observed maximum pitch step of 86.55
versus 7.95 degrees in the gradual mining trials. Measurement windows start
asynchronously after the test phase request; they do not cover every join,
teleport or user-controlled rotation.

Compat.3 is installed in Modrinth's **Fabric 26.3** profile, after verifying no
Java process used it and that the old JAR matched its known hash. Compat.2 is
preserved in `.codex-backups/gradual-compat.3/`, outside `mods`. Exactly one standalone
Baritone JAR remains. Restart the instance and use `#gradual status` to confirm
this build. Complete source and reproducible offline overlay tooling are preserved.
NAS memory reads currently return internal errors; this document and the tracked
benchmark report preserve the local checkpoint.

## Keep normal sprint and parkour — 2026-10-01 (compat.4)

Andre rejected the automatic no-sprint/no-parkour preset because it unnecessarily
slows travel. Compat.4 removes both assignments from GradualLook.enable. Starting
the game or running `#gradual on` preserves the user's allowSprint/allowParkour
values. The selected profile enables both. Matching-view mining and the existing
12/8-degree ground aiming limits remain; no artificial movement-speed modifier,
additional mining delay, or parkour restriction is added.

The separate `reports/2026-10-01-travel.md` benchmark records actual sprint ticks,
airborne ticks, arrival age, rotation packets and mining results. Its parkour
fixture has a three-block trench and requests a normal four-block sprint jump;
the strict trial arrives with 57 sprint ticks and 11 airborne ticks, no flags,
and measured yaw steps at most 11.85 degrees. Unit/runtime/sign regression checks
still pass. Realistic appearance remains an unmeasured subjective property; the
change restores travel options while retaining less abrupt aiming.

The shipped compat.4 SHA-256 is
`4a69e75eaf0105d48281809b4c14cd9ab0f2a2761e7bd767f160373e841790b6`.
Normal sprint-enabled travel/parkour and mining complete. Mining retains a SprintE
wall-collision flag in all three profiles, despite zero block restorations. An
experimental hard-wall sprint-stop hook (SHA-256
`4f7d1624a89ad91e36e213623a1622117cca3c13d5951cd9e38e4719770303c4`)
did not remove that flag and is not shipped. Its trials remain in the report as
an unsuccessful experiment. No extra sprint suppression is installed.
The earlier compat.3 report and artifacts remain historical evidence, including
its slower no-sprint profile and failed prototype; they are not rewritten as
measurements of this new build.

Compat.4 is installed in the existing Fabric 26.3 profile. `baritone/settings.txt`
explicitly retains `allowSprint true` and `allowParkour true`; other settings are
preserved. The verified old compat.3 JAR and settings are backed up outside mods
in `.codex-backups/travel-compat.4/`. Only one standalone Baritone JAR remains.
The measured diagonal goal takes 71 movement ticks with sprint versus 91 in the
old walking-only mode, in one finite comparison. The shipped parkour trial has
57 sprint ticks, 11 airborne ticks, and completes the gap. All three shipped
mining-profile trials remove nine of nine blocks with zero restorations; each
still logs one SprintE wall flag. That finding is retained rather than hidden
by disabling sprint globally. Runtime/sign/input/geometry checks pass for the
exact shipped hash. Restart the profile and use `#gradual status`.


## Accurate ground steering while turning — 2026-10-01 (compat.5)

Andre approved the existing appearance but reported circling, missing waypoints
and replanning. The tight clean-room switchback route reproduced the regression:
compat.4 took 205 movement ticks and traveled 47.04 blocks, with one replan and
seven logged off-path ticks. The ordinary stock control took 127 ticks and
33.56 blocks without replanning. Increasing the response of small yaw corrections
alone did not improve it and is not shipped.

Compat.5 retains the exact original aiming step, angle caps and easing curve.
Before the normal input handler applies travel keys, it chooses the nearest of
eight digital keyboard headings relative to the actual eased aim for that tick.
This follows the intended world heading to within 22.5 degrees while the camera
turns. Minecraft still normalizes the vector and performs ordinary physics; the
transmitted view and physics rotation remain consistent. No extra packet,
movement-speed multiplier or hidden instant-turn physics is introduced.

Only current-tick non-interaction ground travel is remapped. Requested jumps,
airborne movement, vehicle input, flight, mining and placement retain their
existing behavior. State clears at each tick, world change and mode toggle.
Sprint and parkour remain enabled and their settings are preserved. The source
hook is InputOverrideHandler.onTick; the matching pinned optimized hook is in
baritone/fj.class. The overlay still changes eight original classes and two
metadata files, adding six classes. No new input class or bundled library changes.

The exact compat.5 SHA-256 is
`54f26bc538be370e76097c43cd2a5ccdfab79b2b787cb8cb7f7c5e950c8242ab`.
It completes the tight route in 130 ticks over 34.74 blocks, with zero off-path
ticks, replans or backtracks. That is a finite 37% reduction in elapsed ticks
versus compat.4, close to the 127-tick stock control. A prior linear-yaw-plus-
steering experiment completed in 129 ticks; original easing was restored to
preserve the user's approved appearance. Measurements are finite fixtures,
not a guarantee for every terrain, latency or undisclosed server stack.

The full report and every prototype/control input hash are recorded in
`../andre-anticheat/reports/2026-10-01-navigation.md` and its JSON companion.
Runtime checks include 142,618 geometry/steering assertions, 123 real-component
assertions, all 128 real keyboard combinations, supported Fabric/Mixin startup,
100 patched/100 stock sign round trips and independent reachability API compile.
A complete Gradle rebuild remains unperformed. Wall sprint flags remain recorded.

Final regression trials also complete the wider corner route in 135 ticks over
36.24 blocks (compat.4:149 ticks/40.42 blocks), the three-block trench jump in
50 ticks with 47 actual sprint ticks/11 airborne ticks, and the unchanged diagonal
goal in 71 ticks with 70 sprint ticks. Mining removes all nine targets with nine
server-confirmed AIR states, 63 normal Punch sends, nine cobblestone and zero
client restorations. The final mining and diagonal trials log no flags; tighter
corners still log one SprintE wall flag, while the concurrent wider-corner/parkour
trials retain startup Timer/TimerLimit flags. All outcomes and hashes are preserved.
These are compatibility/navigation regressions, not a human classifier benchmark.

Installed in the Modrinth Fabric 26.3 profile after checking that its Java process
was closed. Verified the installed JAR against the exact tested SHA-256, one
standalone Baritone JAR, and unchanged settings including allowSprint/allowParkour
true. The previous compat.4 JAR and settings are backed up outside mods at
`.codex-backups/navigation-compat.5/`. Restart and use `#gradual status`.
