package dev.wildware.udea.physics2d

// One target's half of the name mapping `src/box2dMain` is written against. `box2d-jni` publishes
// Box2D for the desktop JVM in the `box2d` package and, as an Android AAR, the identical API in the
// `box2dandroid` package, so no source set shared by both targets can import either. Each target's
// `Box2DBindings.kt` maps the same names onto its own package. The two files are identical apart
// from the package, and `Box2DBindingsMirrorTest` fails the build if they stop being so.
//
// Every alias names a `Raw` class: the static, address-based entry points. The object API
// allocates a Java wrapper for every struct it returns, which on the per-tick write-back is
// several allocations per body per tick; the `Raw` calls take and return plain addresses.

internal typealias B2World = box2d.B2_World.Raw
internal typealias B2WorldDef = box2d.b2WorldDef.Raw
internal typealias B2Body = box2d.B2_Body.Raw
internal typealias B2BodyDef = box2d.b2BodyDef.Raw
internal typealias B2Shape = box2d.B2_Shape.Raw
internal typealias B2ShapeDef = box2d.b2ShapeDef.Raw
internal typealias B2Geometry = box2d.B2_Geometry.Raw
internal typealias B2Polygon = box2d.b2Polygon.Raw
internal typealias B2Circle = box2d.b2Circle.Raw
internal typealias B2Capsule = box2d.b2Capsule.Raw
internal typealias B2Segment = box2d.b2Segment.Raw
internal typealias B2Vec2 = box2d.b2Vec2.Raw
internal typealias B2Rot = box2d.b2Rot.Raw
internal typealias B2Transform = box2d.b2Transform.Raw
internal typealias B2RotMath = box2d.B2_Rot.Raw
internal typealias B2ContactEvents = box2d.b2ContactEvents.Raw
internal typealias B2ContactBegin = box2d.b2ContactBeginTouchEvent.Raw
internal typealias B2ContactEnd = box2d.b2ContactEndTouchEvent.Raw
internal typealias B2SensorEvents = box2d.b2SensorEvents.Raw
internal typealias B2SensorBegin = box2d.b2SensorBeginTouchEvent.Raw
internal typealias B2SensorEnd = box2d.b2SensorEndTouchEvent.Raw
internal typealias B2RayResult = box2d.b2RayResult.Raw
internal typealias B2QueryFilter = box2d.b2QueryFilter.Raw
internal typealias B2ShapeProxy = box2d.b2ShapeProxy.Raw
internal typealias B2Base = box2d.B2_Base.Raw
internal typealias B2Version = box2d.b2Version.Raw
internal typealias B2Counters = box2d.b2Counters.Raw

/** An enum rather than a `Raw` class: its constants carry the native body-type values. */
internal typealias B2BodyType = box2d.b2BodyType

/** The overlap-query callback: a Java class the native side calls back into, subclassed once. */
internal typealias B2OverlapCallback = box2d.b2OverlapResultFcnImpl

/** Loads the native library. Idempotent: the loader remembers that it ran. */
internal fun loadBox2DNatives() {
    de.fabmax.box2djni.Loader.load()
}

/** Bytes between consecutive begin-touch events in a contact-event array. */
internal fun contactBeginStride(): Int = box2d.b2ContactBeginTouchEvent.SIZEOF

/** Bytes between consecutive end-touch events in a contact-event array. */
internal fun contactEndStride(): Int = box2d.b2ContactEndTouchEvent.SIZEOF

/** Bytes between consecutive begin-touch events in a sensor-event array. */
internal fun sensorBeginStride(): Int = box2d.b2SensorBeginTouchEvent.SIZEOF

/** Bytes between consecutive end-touch events in a sensor-event array. */
internal fun sensorEndStride(): Int = box2d.b2SensorEndTouchEvent.SIZEOF

/** The native address of a callback object, which is what a `Raw` query takes. */
internal fun addressOf(callback: B2OverlapCallback): Long = callback.address
