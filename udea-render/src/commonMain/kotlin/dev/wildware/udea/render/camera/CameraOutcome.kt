package dev.wildware.udea.render.camera

/**
 * What happened to a camera command, in enough detail for a caller to say why nothing moved.
 *
 * ## Why a command that changes the view returns anything at all
 *
 * `CameraRig.requestLookAt` and `CameraRig.requestFollow` write an `AtomicReference` and return.
 * That is deliberate — the camera may only move at a frame boundary — but it means every reason
 * a request will not take effect is invisible at the call: there may be no rig at all, the rig
 * may never have been bound to a world, the net id may resolve to nothing, or the entity may
 * have no pose for the camera to track. All four look identical from outside: an accepted
 * request, and a camera that stays where it is.
 *
 * That silence is what the agent's `render.set_camera` and `render.follow_entity` were reporting
 * as `ok`. An agent believes it moved the camera, screenshots the wrong part of the world, and
 * reasons on from there; there is no round trip that recovers it, because every later
 * observation is consistent with the camera having moved somewhere uninteresting. So the
 * presentation side answers with a reason, and the toolset turns the reason into a typed error.
 */
public enum class CameraOutcome {

    /** A live camera took the request and the next frame applies it. */
    APPLIED,

    /** No `CameraRig` is wired into this renderer: it draws with a fixed projection. */
    NO_CAMERA,

    /**
     * A rig exists but was never bound to a world.
     *
     * A rig is bound by `RenderSystem.onBind`, which happens when it is registered with the
     * `RenderRegistry` the pipeline is built from. One constructed and handed to a
     * `PresentationControl` without being registered can be *placed* — `requestLookAt` needs no
     * world — but can never *follow*, because following resolves an entity out of a world it
     * does not have.
     */
    CAMERA_UNBOUND,

    /** The net id resolves to no live entity: it is stale, or it was never allocated. */
    UNKNOWN_ENTITY,

    /**
     * The entity is live, but nothing gives it a pose to follow.
     *
     * `Interpolator` reads `PhysicsBody`; an entity without one has no drawn position, so a
     * camera told to follow it would sit exactly where it was for as long as the follow lasted.
     * `moba` is the worked example: its units carry `Position` alone, and its own KDoc recorded
     * "`render.follow_entity` is accepted, answers `ok`, and the camera does not move" as a
     * known defect of the surface. This is that defect made reportable.
     */
    UNFOLLOWABLE,
}
