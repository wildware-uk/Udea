package dev.wildware.udea.dsl

/**
 * Classes annotated with this annotation will have a builder generated that can
 * be used in the asset script environment.
 * */
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPEALIAS)
annotation class CreateDsl
