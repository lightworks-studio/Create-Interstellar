# 0002 — Distinct tank behaviour types + tag-validated thruster oxidizer

## Context

`RocketThrusterBlockEntity` has two fluid tanks (fuel, oxidizer). Both were created with
Create's `SmartFluidTankBehaviour.single(...)`, which always uses the same static
`BehaviourType`. Create stores behaviours in a map keyed by that type, so the second tank
silently overwrote the first: only one of the two was ticked, saved or loaded (both also
shared the NBT key `"Tanks"`). Tank contents were lost on every save/load cycle.

Separately, the oxidizer validator accepted only `NorthstarFluids.LIQUID_OXYGEN`, so pumping
oxygen gas (`northstar:oxygen`) into a thruster was rejected and the tank stayed at 0 —
contradicting the design note that the oxidizer tags are the validation source.

## Decision

- Create each tank with its own named `BehaviourType`: `"Fuel"` and `"Oxidizer"`, via the
  full `SmartFluidTankBehaviour` constructor (no `.single()` factory for multi-tank BEs).
- Validate the oxidizer by tag, not by concrete fluid: any fluid in `c:oxygen`
  (`NorthstarFluidTags.C_OXYGEN`) **or** `c:liquid_oxygen`
  (`NorthstarFluidTags.C_LIQUID_OXYGEN`). This accepts oxygen gas and liquid oxygen alike.

## Consequences

- Both tanks now tick, save, load and sync independently; NBT keys are `"FuelTanks"` /
  `"OxidizerTanks"`.
- Existing saves do not carry tank content forward (previously only one of the two was
  persisted); a one-time empty-tank reset on first launch after this change is expected.
- Any future block entity with multiple `SmartFluidTankBehaviour`s must use distinct named
  `BehaviourType`s, never repeated `.single()` calls.
