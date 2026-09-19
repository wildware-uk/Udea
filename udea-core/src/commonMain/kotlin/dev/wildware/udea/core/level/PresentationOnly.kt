package dev.wildware.udea.core.level

/**
 * Marks a component as presentation state: something the renderer keeps on an entity that is not
 * part of the game, and that a level therefore never carries (issue #246).
 *
 * `udea-render`'s pose records are the case it exists for. After one tick every `Transform3D`
 * carries an `Interp3D` and every `PhysicsBody` an `Interp`, holding where the entity stood at the
 * last tick boundaries so a frame can be drawn between them. They are derived from the simulated
 * components and rebuilt from them on the next tick, so writing them into a level would save
 * nothing a load needs - and they are not `@Serializable`, so without this marker [LevelService]
 * refused to save any world that had ticked with the renderer in it.
 *
 * [LevelService.saveNow] leaves a marked component out of the level. Every other component still
 * has to be named by a generated level list, or the save fails and names it: that refusal is what
 * stops a level silently losing game state, and this marker does not weaken it for anything that
 * has not opted out.
 *
 * ## What may carry it
 *
 * Only a component that the game can recreate on its own after a load - one whose values are
 * derived from components the level does save, by a system that adds it when it is missing. A
 * component that says *what* to draw (a model, a sprite) is content, not presentation state, even
 * though it lives in `udea-render`: nothing would put it back, so marking it would load a world
 * with its models gone.
 *
 * It lives here, and not in `udea-render`, because `udea-core` cannot depend on the renderer and
 * the level code has to be able to ask the question.
 */
public interface PresentationOnly
