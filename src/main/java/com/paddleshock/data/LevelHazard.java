package com.paddleshock.data;

/** An arena's interactive hazard, on top of its physics multipliers - see docs/specs/05-hazard-arenas.md. */
public enum LevelHazard {
    NONE,
    /** Two mirrored bumpers sliding side to side, one in each half (see {@code sim.Bumpers}). */
    BUMPERS,
    /** Icy paddles: movement has momentum (see {@code sim.IceSlide}). */
    ICE
}
