package dev.wildware.udea

fun camelCaseToSentence(input: String): String {
    return input.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        .replaceFirstChar { it.titlecase() }
}

fun camelCaseToTitle(input: String): String {
    return input.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
}
