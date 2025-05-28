package dev.wildware

/**
 * Marks a class or property as a reference, meaning it will be treated as a value, instead of an object.
 *
 * If used on a class, all fields of that class will be treated as references.
 * */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.TYPE)
annotation class KReference

/**
 * Marks a class or property as provided, meaning it will be injected by the framework.
 *
 * If used on a class, all instances of that class will be treated as provided.
 * */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD)
annotation class KProvided
