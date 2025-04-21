package dev.wildware.udea

import com.intellij.openapi.vfs.VirtualFile

fun VirtualFile.isEmpty()= this.length == 0L