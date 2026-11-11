# Module: Reaction Wheel

The reaction wheel (`northstar:reaction_wheel`) is a kinetic block that stabilizes the
attitude of Sable sublevels (contraptions) around the world-Y axis. It is ported from
CreateKinetic's gyroscope; all config lives in this mod's `ServerConfig` under the
`reactionWheel` group.

| Class | Role |
| --- | --- |
| `block/tech/reaction_wheel/ReactionWheelBlock` | `KineticBlock`, `DirectionalBlock` (`FACING`), noOcclusion, softMetal, stress impact from config |
| `block/tech/reaction_wheel/ReactionWheelBlockEntity` | `KineticBlockEntity implements BlockEntitySubLevelActor`; per-gyro state + redstone signals |
| `block/tech/reaction_wheel/ReactionWheelController` | Per-sublevel PD stabilizer running on the physics tick |
| `block/tech/reaction_wheel/ReactionWheelRenderer` / `ReactionWheelVisual` | Flywheel + shaft instances for sublevels |

## Block entity state

- `effectiveSpeed` — interpolated float of the current rotation speed (RPM), from the kinetic network.
- `angle` — accumulated flywheel angle, advanced in `tick()` by `effectiveSpeed * 3/10` per partial tick.
- `stabilizedPercent` — byte 0–100, written by the controller every physics tick for all gyros on the sublevel.
- `ownSignalX` / `ownSignalZ` — bytes 0–15, read from redstone input at the BE's position; select which axes this gyro participates in (0 = fully active, 15 = axis disabled).
- `currentRpmScale()` = `min(|effectiveSpeed| / reactionWheelReferenceRpm, 1)`.

## Controller (`ReactionWheelController`)

One instance per `ServerSubLevel`, held in a static `WeakHashMap`; created on demand via
`of(subLevel)` and detached on sublevel removal and server start/stop.

Per physics tick (given the sublevel's rigid-body handle):

1. **Fleet state** — over all gyro BEs on the plot: `totalCapacity = Σ authority(rpmScale)`,
   where `authority = rpmScale * reactionWheelAuthorityPerUnit`; `meanRpmScale` is the average;
   `kX/kZ` are capacity-weighted `(1 - ownSignal/15)` per axis.
2. **Gains** — with ship inertia `I` (mean of the inertia tensor diagonal):
   `scale = min(meanRpmScale, totalCapacity / I)`, `kp = ω²·I·scale`, `kd = 2ζωI·scale`.
   Under-powered fleets degrade gracefully: smaller scale, slower correction.
3. **Disturbance estimate** — from the change in local angular velocity minus the delta-omega
   predicted by the last applied impulse (via the inverse inertia tensor), projected off the
   body-up axis, EMA-filtered with `reactionWheelFeedForwardSmoothing`. Capped at magnitude 1.
4. **Correction impulse** — restoring term from the tilt error axis (`kp · dt`) plus damping
   term (`-kd · ω_local · dt`), clamped so it never reverses the current angular velocity,
   minus `scale * reactionWheelFeedForwardGain * filteredDisturbance`. The Y component is
   re-derived to keep the impulse in the plane perpendicular to body-up. Applied with
   `handle.applyTorqueImpulse(...)` in the local frame; axes are scaled by `kX/kZ`.
5. **Stabilized %** — `100 · (1 - min(1, phaseDistance / ω_target))` where phase distance
   combines tilt and angular-velocity error; pushed to every gyro BE on the plot.

If total capacity is 0, mass data is invalid, or gains scale to 0, the observer resets and
all gyros report 0%.

## Config (`ServerConfig.reactionWheel`)

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `referenceRpm` | 256 | 1–4096 | RPM at which a gyro reaches 100% effectiveness |
| `omegaTarget` | 3.0 | 0.1–10 | Closed-loop natural frequency (rad/s) |
| `dampingRatio` | 0.9 | 0.1–2 | Closed-loop damping ratio |
| `authorityPerUnit` | 5000 | 100–1,000,000 | Ship inertia (kg·m²) one gyro fully stabilizes at reference RPM |
| `feedForwardGain` | 1.0 | 0–1 | Fraction of observed disturbance cancelled via feed-forward |
| `feedForwardSmoothing` | 0.2 | 0.01–1 | EMA factor for the disturbance estimate |
| `stressImpact` | 16 | 0–1024 | Stress in SU per RPM (registered on the block) |

## Rendering

`ReactionWheelVisual` creates two oriented instances: a flywheel
(`NorthstarPartialModels.GYROSCOPE_FLYWHEEL`, model `block/gyroscope/flywheel`) lifted when
the block faces down, and a half shaft (`AllPartialModels.SHAFT_HALF`). The flywheel spins
with the BE's interpolated speed + accumulated angle; the shaft follows the kinetic network
angle around Y. `FACING = UP` renders the inverted variant (flywheel near the top).

## Assets & models

All assets live under `assets/northstar/` — this mod has no CreateKinetic dependency, so any
`createkinetic:` resource reference fails and renders as a pink square.

| Asset | Path (`assets/northstar/`) | Purpose |
| --- | --- | --- |
| Blockstate | `blockstates/reaction_wheel.json` | `facing=down` → `northstar:block/gyroscope/block`; `facing=up` → same with `"x": 180`. Hand-written; datagen blockstate suppressed via `.blockstate(NorthstarDataGenModels.manualModel())` on the registration. |
| Frame model | `models/block/gyroscope/block.json` | Base frame (uses `gyroscope/block.png` + `gyroscope/redstone_panel.png`). Used by both block and item models. |
| Flywheel partial | `models/block/gyroscope/flywheel.json` (+ `.obj`, `.mtl`) | OBJ loader model; tex 0 = `block/flywheel.png`, tex 1/2 = Create's axis textures (`.mtl` maps m_0/m_1/m_2 → #0/#1/#2). |
| Redstone strips partial | `models/block/gyroscope/indicator.json` | Thin strips on N/S faces, tinted by the BE renderer from `redstone_panel.png`. |
| Core partial (sublevel) | `models/partial/reaction_wheel_core.json` | Used by `ReactionWheelVisual`; texture `block/reaction_wheel.png`. |
| Textures | `textures/block/{reaction_wheel,flywheel,gyroscope/block,gyroscope/redstone_panel}.png` | First two copied from the CreateKinetic reference. |

### Item model & renderer

- Hand-written `models/item/reaction_wheel.json` (parent `northstar:block/gyroscope/block`,
  display transforms matching the CreateKinetic item model). It shadows the datagen-generated
  item model because `src/main/resources` precedes `src/generated` on the classpath
  (`build.gradle.kts`).
- `NorthstarClient` wires the item renderer (mirroring CreateKinetic's `KineticClient`):
  - `RegisterClientExtensionsEvent` registers a `BlockEntityWithoutLevelRenderer`
    (`ReactionWheelItemRenderer`) for the block item via `IClientItemExtensions`.
  - `ModelEvent.ModifyBakingResult` wraps the baked inventory model in
    `CustomRenderedItemModel`, so the flywheel/shaft/core partials render on top of the
    frame in hand, GUI, and world.
