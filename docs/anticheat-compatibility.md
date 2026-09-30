# Anti-cheat compatibility work — 2026-09-30

Andre authorized committing/pushing the sign privacy patch and starting further
work against the anti-cheats on his own server. The plugin names, exact builds,
configuration, flag logs, backend/proxy topology, and installed client mods are
not yet available. DonutSMP production parity has not been established. This is
an initial compatibility investigation, not a comprehensive security audit or a
verified universal bypass.

## Saved state

- Worktree: `/var/home/andre/Projects/baritone-sign-privacy`.
- Remote: `https://github.com/AndreNijman/baritone.git`.
- Branch: `fix/mc265322-sign-privacy`.
- Base: official Baritone v1.20.0, upstream commit `25111dae`, Minecraft 26.3.
- Sign patch committed and pushed as `8d3d30a7`.
- Built sign-only candidate: `dist/baritone-api-fabric-1.20.0-signprivacy.1.jar`.
- Candidate SHA-256: `9698ef0c506cdc715d10cc97bc11cd5a4685f44d163dd52bb18e659871e49840`.
- Source, positive-control pack, and test guide are preserved in the repository
  and generated locally. Generated binaries are ignored by Git.
- Persistent-memory boot/read/write calls currently return MCP internal errors.
  This repository record preserves the handoff; it does not imply memory was saved.

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

**This movement fix is source-only. It is not included in the sign-only JAR.**
A complete build and in-game pathfinding/physics comparison are still required.
No measured anti-cheat flag reduction is claimed.

## Further source review

The public Grim source was inspected at commit
`61117c2865f603f4990df09fdaa4adeaf7d8a557`. Its modern input transformer normalizes
keyboard vectors and applies sneak slowdown later, matching the vanilla path.
That is useful independent evidence for the input correction. It does not
establish the behavior of a private fork or a different installed version.

| Surface | Evidence inspected | Next validation |
| --- | --- | --- |
| Sign translation | Baritone helper/mixin; CheckHacks fallback comparison | Launch Fabric and compare positive-control pack scans |
| Movement input | PlayerMovementInput; actual 26.3 KeyboardInput/LocalPlayer bytecode; Grim ModernInputTransformer | Build source fix, compare walking/diagonal/sneak paths in game |
| Rotations | LookBehavior applies targets before packet updates; smoothLook runs in POST | Correlate server rotation/placement flags with packet order |
| Placement | BlockPlaceHelper invokes the game controller; Grim RotationPlace/MultiPlace validate interaction state | Reproduce any placement flags with actual logs and configuration |
| Mining | BlockBreakHelper uses controller progress/delay; Grim FastBreak checks progress/timing | Reproduce observed mining flags without assuming thresholds |
| Inventory | allowInventory defaults false; optional stationary gating exists | Test only the inventory behavior enabled on this client/server |
| Protocol/versions | Client 26.3 uses modern key-state reporting; current Grim source contains 26.3 cases | Identify backend/proxy/PacketEvents/ViaVersion versions and placement |

`antiCheatCompatibility` already defaults to true. Its name is not a guarantee
that every check or private server modification is covered. `smoothLook` changes
the local camera after rotation packets; enabling it alone does not smooth the
rotations observed by the server.

## Information needed for server-specific changes

Provide the plugin names/builds, local configuration paths, recent flag logs from
an authorized test account, backend Minecraft version, proxy/ViaVersion setup,
and a local Minecraft 26.3/Fabric instance path. Keep secrets and player-identifying
data out of shared excerpts. These facts determine which tests and changes are
relevant; guessed plugin defaults would not establish compatibility.

Next: perform runtime verification of the sign patch, complete a build containing
the input fix, then reproduce each observed server flag separately against a
vanilla control and the patched client. Save actual scan/flag outcomes and identify
the concrete source mismatch before changing packet or gameplay behavior.

## Sources

- [Baritone movement input at the reviewed base](https://github.com/cabaletta/baritone/blob/25111dae/src/main/java/baritone/utils/PlayerMovementInput.java)
- [Baritone look behavior](https://github.com/cabaletta/baritone/blob/25111dae/src/main/java/baritone/behavior/LookBehavior.java)
- [Baritone settings](https://github.com/cabaletta/baritone/blob/25111dae/src/api/java/baritone/api/Settings.java)
- [Pinned Grim modern input transformer](https://github.com/GrimAnticheat/Grim/blob/61117c2865f603f4990df09fdaa4adeaf7d8a557/common/src/main/java/ac/grim/grimac/predictionengine/predictions/input/impl/ModernInputTransformer.java)
- [Pinned Grim RotationPlace](https://github.com/GrimAnticheat/Grim/blob/61117c2865f603f4990df09fdaa4adeaf7d8a557/common/src/main/java/ac/grim/grimac/checks/impl/scaffolding/RotationPlace.java)
- [Pinned Grim FastBreak](https://github.com/GrimAnticheat/Grim/blob/61117c2865f603f4990df09fdaa4adeaf7d8a557/common/src/main/java/ac/grim/grimac/checks/impl/breaking/FastBreak.java)
