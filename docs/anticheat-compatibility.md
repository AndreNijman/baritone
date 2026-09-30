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
