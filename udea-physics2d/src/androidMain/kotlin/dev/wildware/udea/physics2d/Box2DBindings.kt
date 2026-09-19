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

internal typealias B2World = box2dandroid.B2_World.Raw
internal typealias B2WorldDef = box2dandroid.b2WorldDef.Raw
internal typealias B2Body = box2dandroid.B2_Body.Raw
internal typealias B2BodyDef = box2dandroid.b2BodyDef.Raw
internal typealias B2Shape = box2dandroid.B2_Shape.Raw
internal typealias B2ShapeDef = box2dandroid.b2ShapeDef.Raw
internal typealias B2Geometry = box2dandroid.B2_Geometry.Raw
internal typealias B2Polygon = box2dandroid.b2Polygon.Raw
internal typealias B2Circle = box2dandroid.b2Circle.Raw
internal typealias B2Capsule = box2dandroid.b2Capsule.Raw
internal typealias B2Segment = box2dandroid.b2Segment.Raw
internal typealias B2Vec2 = box2dandroid.b2Vec2.Raw
internal typealias B2Rot = box2dandroid.b2Rot.Raw
internal typealias B2Transform = box2dandroid.b2Transform.Raw
internal typealias B2RotMath = box2dandroid.B2_Rot.Raw
internal typealias B2ContactEvents = box2dandroid.b2ContactEvents.Raw
internal typealias B2ContactBegin = box2dandroid.b2ContactBeginTouchEvent.Raw
internal typealias B2ContactEnd = box2dandroid.b2ContactEndTouchEvent.Raw
internal typealias B2SensorEvents = box2dandroid.b2SensorEvents.Raw
internal typealias B2SensorBegin = box2dandroid.b2SensorBeginTouchEvent.Raw
internal typealias B2SensorEnd = box2dandroid.b2SensorEndTouchEvent.Raw
internal typealias B2RayResult = box2dandroid.b2RayResult.Raw
internal typealias B2QueryFilter = box2dandroid.b2QueryFilter.Raw
internal typealias B2ShapeProxy = box2dandroid.b2ShapeProxy.Raw
internal typealias B2Base = box2dandroid.B2_Base.Raw
internal typealias B2Version = box2dandroid.b2Version.Raw
internal typealias B2Counters = box2dandroid.b2Counters.Raw

/** An enum rather than a `Raw` class: its constants carry the native body-type values. */
internal typealias B2BodyType = box2dandroid.b2BodyType

/** The overlap-query callback: a Java class the native side calls back into, subclassed once. */
internal typealias B2OverlapCallback = box2dandroid.b2OverlapResultFcnImpl

/** Loads the native library. Idempotent: the loader remembers that it ran. */
internal fun loadBox2DNatives() {
    de.fabmax.box2dandroid.Loader.load()
}

/** Bytes between consecutive begin-touch events in a contact-event array. */
internal fun contactBeginStride(): Int = box2dandroid.b2ContactBeginTouchEvent.SIZEOF

/** Bytes between consecutive end-touch events in a contact-event array. */
internal fun contactEndStride(): Int = box2dandroid.b2ContactEndTouchEvent.SIZEOF

/** Bytes between consecutive begin-touch events in a sensor-event array. */
internal fun sensorBeginStride(): Int = box2dandroid.b2SensorBeginTouchEvent.SIZEOF

/** Bytes between consecutive end-touch events in a sensor-event array. */
internal fun sensorEndStride(): Int = box2dandroid.b2SensorEndTouchEvent.SIZEOF

/** The native address of a callback object, which is what a `Raw` query takes. */
internal fun addressOf(callback: B2OverlapCallback): Long = callback.address
