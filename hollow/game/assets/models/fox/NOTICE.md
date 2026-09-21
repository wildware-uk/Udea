# Fox.glb: licence and attribution

`Fox.glb` is the **Fox** sample model from the Khronos Group's glTF sample assets, committed here
unchanged (issue #240).

- Source: <https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Fox>, file
  `Models/Fox/glTF-Binary/Fox.glb`, at commit `81e8b567643b5166e6ff40024e4ff71ad4b18676`.
- SHA-256 of the committed file: `d97044e701822bac5a62696459b27d7b375aada5de8574ed4362edbba94771f7`.

## Who made it, and under which licence

Copied from that directory's own `metadata.json` and `LICENSE.md`:

| Part | Author | Year | Licence |
|---|---|---|---|
| Model | PixelMannen (<https://opengameart.org/content/fox-and-shiba>) | 2014 | CC0 1.0 Universal |
| Rigging and animation | tomkranis (<https://sketchfab.com/3d-models/low-poly-fox-by-pixelmannen-animated-371dea88d7e04a76af5763f2a36866bc>) | 2014 | CC BY 4.0 |
| Conversion to glTF | @AsoboStudio and @scurest (<https://github.com/KhronosGroup/glTF-Sample-Models/pull/150#issuecomment-406300118>) | 2017 | CC BY 4.0 |

The file's own `asset.copyright` field reads: *"CC-BY 4.0 Model by PixelMannen
https://opengameart.org/content/fox-and-shiba and @tomkranis
https://sketchfab.com/3d-models/low-poly-fox-by-pixelmannen-animated-371dea88d7e04a76af5763f2a36866bc
and @AsoboStudio with @scurest
https://github.com/KhronosGroup/glTF-Sample-Models/pull/150#issuecomment-406300118"*.

Because the rigging and the glTF conversion are CC BY 4.0, this repository treats the whole file
as **CC BY 4.0**: <https://creativecommons.org/licenses/by/4.0/legalcode>. Reuse it with the
attribution above. It is not covered by this repository's MIT licence, and no change was made to
it.

## Why Hollow has its own copy, and why it is not CC0

Hollow's design (`docs/superpowers/specs/2026-09-19-hollow-3d-game-design.md`) asks for CC0 art and
names this fox, the Khronos glTF sample, as the creature. So the fox is the one piece of Hollow's
art under CC BY 4.0, and that is recorded here rather than left to be found: the attribution above
travels with the file.

An asset root belongs to one game, so Hollow cannot read `moba/game/assets/models/fox/` without
making one game's build depend on another game's asset tree; the file is copied instead.
`FoxAssetTest` in `hollow:game` fails when this copy stops being byte-identical to moba's, or stops
matching the SHA-256 recorded above.
