package dev.wildware.udea.assets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Snapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiClass
import dev.wildware.udea.*
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.OutlineButton
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

class LevelEditor : AssetEditor {
    @Composable
    override fun AssetFileEditor.CreateAssetEditor() {
        var level by remember {
            mutableStateOf(
                Level(currentState[0] as List<KClass<out IntervalSystem>>, currentState[1] as List<Snapshot>)
            )
        }

        var selectedEntity by remember { mutableStateOf<Snapshot?>(null) }

        Column {
            Row {
                OutlineButton(onClick = {
                    modified = true
                    level = level.copy(entities = level.entities + Snapshot(emptyList(), emptyList()))
                    updateCurrentState(level)
                }) {
                    Label("Add Entity")
                }
            }

            EntityList(level.entities, onEntitySelected = {
                selectedEntity = it
            })

            if (selectedEntity != null) {
                SnapshotEditor(selectedEntity!!)
            }
        }
    }

    @Composable
    fun EntityList(snapshots: List<Snapshot>, onEntitySelected: (Snapshot) -> Unit = {}) {
        Column {
            snapshots.forEach { snapshot ->
                Box(
                    modifier = Modifier.padding(8.dp)
                        .clickable(onClick = {
                            onEntitySelected(snapshot)
                        })
                ) {
                    Label("Entity")
                }
            }
        }
    }

    @Composable
    fun AssetFileEditor.SnapshotEditor(snapshot: Snapshot) {
        var showComponentSelector by remember { mutableStateOf(false) }
        var componentBuilder by remember { mutableStateOf<KObjectBuilder?>(null) }

        Column {
            Label("Entity Selected")

            OutlineButton(onClick = {
                showComponentSelector = true
            }) {
                Label("Add Component")
            }

            if (showComponentSelector) {
                ComponentSelector(onComponentSelected = {
//                    componentBuilder = KObjectBuilder.fromPsiClass(it)
                })
            }

            if(componentBuilder != null) {
//                ComponentBuilder(componentBuilder)
            }

            snapshot.components.forEach {
                Box(modifier = Modifier.padding(16.dp)) {
                    Label(it::class.simpleName!!)
                }
            }
        }
    }

    @Composable
    fun AssetFileEditor.ComponentSelector(onComponentSelected: (PsiClass) -> Unit = {}) {
        var components: List<PsiClass>? by remember { mutableStateOf(null) }

        remember {
            pooled {
                components = findClassesOfType(project, Component::class.jvmName)
            }
        }

        Column {
            if (components == null) {
                Label("Loading components...")
            } else {
                components!!.forEach {
                    Box(
                        modifier = Modifier
                        .padding(8.dp)
                        .clickable(onClick = { onComponentSelected(it) })
                    ) {
                        Label("${it.name}")
                    }
                }
            }
        }
    }

    private fun AssetFileEditor.updateCurrentState(level: Level) {
        currentState = arrayOf(
            level.systems,
            level.entities
        )

        WriteCommandAction.runWriteCommandAction(project) {
            val asset = Asset(assetId, level, assetType)
            document.setText(Json.toJson(asset))
            modified = false
        }
    }
}
