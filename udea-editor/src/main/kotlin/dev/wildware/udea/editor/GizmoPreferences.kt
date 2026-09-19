package dev.wildware.udea.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.udea.editor.gizmo.FieldWrite
import dev.wildware.udea.editor.gizmo.Snap
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.math.PI
import kotlin.math.round

/** Which axes the Scene tab's handles follow: the world's, or each entity's own. */
internal enum class GizmoAxes { World, Local }

/**
 * The Scene tab's snapping and axes (issue #236): per project and per person, never game data.
 *
 * Grid snapping rounds what a gizmo writes as a position or a size ([Snap.Grid]) to [gridStep] world
 * units, and angle snapping rounds what it writes as an angle ([Snap.Angle]) to [angleStepDegrees].
 * Each is off until turned on, from the toolbar over the Scene tab, and Ctrl held during a drag turns
 * both off for that move. [axes] is the toolbar's world/local switch.
 *
 * ## Where they are kept
 *
 * In a small properties file a launcher names ([load]), beside the game rather than in it: the editor
 * has no other store - its panel layout is kept in memory for the run - and a person's grid is not
 * something a level or an asset should carry. Every change is written at once, so what a person set
 * is there the next time the editor opens on that project. With no file ([GizmoPreferences]'s own
 * constructor) they last for the run, which is what a test wants.
 *
 * Render thread only, like the window that changes them.
 */
public class GizmoPreferences private constructor(private val file: Path?) {

    /** Preferences that last for the run and are written nowhere. */
    public constructor() : this(null)

    private var gridSnapState by mutableStateOf(false)
    private var gridStepState by mutableStateOf(DEFAULT_GRID_STEP)
    private var angleSnapState by mutableStateOf(false)
    private var angleStepState by mutableStateOf(DEFAULT_ANGLE_STEP_DEGREES)
    private var axesState by mutableStateOf(GizmoAxes.World)

    /** Whether positions and sizes snap to [gridStep]. */
    internal var gridSnap: Boolean
        get() = gridSnapState
        set(value) {
            gridSnapState = value
            save()
        }

    /** The grid, in world units. Always more than zero. */
    internal var gridStep: Float
        get() = gridStepState
        set(value) {
            require(value > 0f && value.isFinite()) { "a grid step is a positive number of world units, not $value" }
            gridStepState = value
            save()
        }

    /** Whether angles snap to [angleStepDegrees]. */
    internal var angleSnap: Boolean
        get() = angleSnapState
        set(value) {
            angleSnapState = value
            save()
        }

    /** The angle step, in degrees, because that is how a person types one. Always more than zero. */
    internal var angleStepDegrees: Float
        get() = angleStepState
        set(value) {
            require(value > 0f && value.isFinite()) { "an angle step is a positive number of degrees, not $value" }
            angleStepState = value
            save()
        }

    /** Which axes the handles follow. */
    internal var axes: GizmoAxes
        get() = axesState
        set(value) {
            axesState = value
            save()
        }

    /**
     * [write]'s value as the editor sends it: rounded to the step its [FieldWrite.snap] names when
     * that snapping is on, and exactly as the gizmo computed it when it is off, when the write asks
     * for no snapping, or when [bypass] - Ctrl held - says so.
     */
    internal fun snapped(write: FieldWrite, bypass: Boolean): Float {
        if (bypass) return write.value
        return when (write.snap) {
            Snap.None -> write.value
            Snap.Grid -> if (gridSnap) round(write.value / gridStep) * gridStep else write.value
            Snap.Angle -> {
                val step = angleStepDegrees * RADIANS_PER_DEGREE
                if (angleSnap) round(write.value / step) * step else write.value
            }
        }
    }

    private fun save() {
        val target = file ?: return
        val properties = Properties()
        properties.setProperty(GRID_SNAP, gridSnap.toString())
        properties.setProperty(GRID_STEP, gridStep.toString())
        properties.setProperty(ANGLE_SNAP, angleSnap.toString())
        properties.setProperty(ANGLE_STEP, angleStepDegrees.toString())
        properties.setProperty(AXES, axes.name)
        target.parent?.let(Files::createDirectories)
        Files.newBufferedWriter(target).use { properties.store(it, HEADER) }
    }

    override fun toString(): String =
        "GizmoPreferences(grid=${if (gridSnap) gridStep else "off"}, angle=${if (angleSnap) angleStepDegrees else "off"}, $axes, file=$file)"

    public companion object {

        /**
         * The preferences kept in [file]: what it holds, or the defaults when it does not exist yet.
         * Every change is written back to it.
         *
         * @throws IllegalArgumentException naming the file and the entry, when an entry holds
         *   something it cannot - a hand edit gone wrong is said, not quietly replaced by a default.
         */
        public fun load(file: Path): GizmoPreferences {
            val preferences = GizmoPreferences(file)
            if (!Files.exists(file)) return preferences
            val properties = Properties()
            Files.newBufferedReader(file).use(properties::load)
            fun entry(key: String): String? = properties.getProperty(key)?.trim()
            fun bad(key: String): Nothing = throw IllegalArgumentException("$file: '$key' holds '${entry(key)}', which it cannot")
            entry(GRID_SNAP)?.let { preferences.gridSnapState = it.toBooleanStrictOrNull() ?: bad(GRID_SNAP) }
            entry(GRID_STEP)?.let { preferences.gridStepState = it.toFloatOrNull()?.takeIf { step -> step > 0f } ?: bad(GRID_STEP) }
            entry(ANGLE_SNAP)?.let { preferences.angleSnapState = it.toBooleanStrictOrNull() ?: bad(ANGLE_SNAP) }
            entry(ANGLE_STEP)?.let { preferences.angleStepState = it.toFloatOrNull()?.takeIf { step -> step > 0f } ?: bad(ANGLE_STEP) }
            entry(AXES)?.let { value -> preferences.axesState = GizmoAxes.entries.firstOrNull { it.name == value } ?: bad(AXES) }
            return preferences
        }

        /** One world unit: a grid a person turning snapping on sees move things, whatever the game's scale. */
        private const val DEFAULT_GRID_STEP: Float = 1f

        /** Fifteen degrees: a twenty-fourth of a turn, the step an editor's angle snap usually starts at. */
        private const val DEFAULT_ANGLE_STEP_DEGREES: Float = 15f

        private const val RADIANS_PER_DEGREE: Float = (PI / 180.0).toFloat()

        private const val GRID_SNAP = "gizmo.grid.snap"
        private const val GRID_STEP = "gizmo.grid.step"
        private const val ANGLE_SNAP = "gizmo.angle.snap"
        private const val ANGLE_STEP = "gizmo.angle.step.degrees"
        private const val AXES = "gizmo.axes"

        private const val HEADER = "The Udea editor's gizmo preferences for this project (issue #236). Not game data."
    }
}
