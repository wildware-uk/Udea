package dev.wildware.udea.render.bytecode

import java.io.File

/**
 * "Resolve families in `onBind`, never in `render`", enforced against compiled bytecode.
 *
 * `World.family { }` builds a family definition and looks it up; in `render` that is a lookup
 * done sixty-plus times a second for a handle that never changes, and it allocates the
 * definition lambda's receiver every time. The old tree did the equivalent per *packet*
 * (`common/.../utils.kt:35`, a linear family scan per inbound message), which is the same
 * defect one layer down.
 *
 * A convention would not survive: the call compiles, works, and is invisible in review. So it
 * is a bytecode rule over the same [ClassRefScanner] the headless gate uses -- the reference
 * is an ordinary `invokevirtual` and there is nowhere for it to hide.
 */
internal object FamilyInRenderRule {

    /** Fleks' `World`, in internal form. */
    const val WORLD_OWNER: String = "com/github/quillraven/fleks/World"

    /** The method that resolves a family. */
    const val FAMILY_METHOD: String = "family"

    /**
     * Every `World.family` call made from a `render` method in [classFiles].
     *
     * Keyed on the method *name* rather than on the declaring interface because a violation
     * is a violation wherever the class sits in the hierarchy, and because a class that
     * implements [dev.wildware.udea.render.RenderSystem] indirectly would otherwise slip
     * through.
     */
    fun violations(classFiles: List<File>): List<TypeUse> = classFiles
        .flatMap { ClassRefScanner.scan(it) }
        .filter { use ->
            use.owner == WORLD_OWNER &&
                use.ownerMember == FAMILY_METHOD &&
                use.member.startsWith("render(")
        }
}
