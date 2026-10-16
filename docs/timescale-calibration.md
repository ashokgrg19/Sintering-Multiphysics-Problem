# Making phase-field sintering runs affordable without losing the physics

Diffuse-interface sintering models couple fast interface relaxation with slow
mass transport. A run must resolve the fast process while lasting long enough
for the slow one, so its cost is set by the **ratio of time scales**, not by
the absolute value of any mobility.

## What does *not* help
Multiplying every mobility (interface, surface, grain-boundary, bulk) by the
same factor only relabels the time unit. The solver takes the same number of
steps.

## What does help

| Knob | Typical gain | Constraint |
|---|---|---|
| Smaller particle radius relative to interface width, R/δ | cost ∝ (R/δ)³ (bulk) to (R/δ)⁴ (surface) | δ/R ≲ 0.15 so interfaces stay thin compared with the particle |
| Faster diffusion relative to interface relaxation | up to ~10x | interface must remain at local equilibrium (diffusion-controlled) |
| Start from touching / slightly overlapping particles | skips an artificial neck-nucleation stage | early neck growth still visible from x/D ≈ 0.1 |

Also check units: if lengths in the geometry are in nm but the equations are
normalized by a characteristic length l ≠ 1 nm, the simulated particle is not
the size you think it is (e.g. R = 15 nm drawn in nm units with l = 1.5 nm is
effectively 22.5 nm).

## Choosing the dominant mechanism
Which mechanism dominates depends on *ratios* of effective transport, as in an
Ashby sintering map. With surface, grain-boundary and bulk mobilities B_s, B_gb, B_v,
interface width δ and radius R:

- surface vs bulk  M_s = B_s·δ / (B_v·R)
- GB vs bulk       M_g = B_gb·δ / (B_v·R)

A practical target for "surface diffusion drives early necking, GB/bulk
transport strong enough for densification" is M_s ≈ 5–10 and M_g ≈ 1–2.

Remember which paths densify: transport from the **grain boundary** to the
neck (GB or bulk diffusion) brings particle centres together; transport from
the **free surface** to the neck (surface diffusion, evaporation–condensation,
bulk from the surface) only grows the neck. Densification therefore requires
the particle centres to be able to move (rigid-body motion or a mechanics
field). A model with fixed centres can round pores but not shrink them,
except by slow outer-to-inner mass redistribution.

## Validating a speed-up
Fit the neck-growth law (x/D)ⁿ ∝ t (`post/neck_exponent.py`):

- n unchanged with respect to the reference run → the speed-up preserved the
  mechanism; accept it.
- n drops toward ~2 → kinetics became interface-controlled; back off.

Also check that the maximum |∇φ| (interface width) stays constant and that the
total solid area drifts by well under 1 %.

## Stopping long runs
In the early stage the pore area can stay almost constant while necks grow,
so "pore area stopped changing" is not equilibrium. Stop on quantities that
track the free energy, e.g. neck ratio and free-surface length, and require
the plateau over consecutive restart windows of increasing length
(`post/stitch_restarts.py --stop-on neck_ratio,surface_length`).
