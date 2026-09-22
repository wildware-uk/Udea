# Human.fbx: licence and attribution

`Human.fbx` and `ClothedLightSkin.png` are from **Quaternius' Animated Man Pack**, which is released
under **CC0 1.0 Universal** (public domain): <https://creativecommons.org/publicdomain/zero/1.0/>.
They are the FBX sample of issue #244: a rigged, animated, textured character that the asset
build publishes as glTF.

- Source page: <https://quaternius.com/packs/animatedman.html>, which states "License CC0".
- Model: `FBX/Animated Human.fbx` in the pack's download folder
  (<https://drive.google.com/drive/folders/1XQ3UpQezkOFDdazv6KK16qp3mBXhO9uO>), SHA-256
  `edd4fde3a73afe2a22ddb5a10a215373a0880d9a4ee32a0815879a260b8e8445`.
- Texture: `OBJ/Textures/ClothedLightSkin.png` in the same folder, committed unchanged, SHA-256
  `c8a975424739500699e27618f1e57ece309dff5ffeff657f4ae3c3c7a309eb57`.

## What was changed

`Human.fbx` is not the published file. `relink.py`, beside it, is the whole change, run with
Blender 5.2.2:

- the published FBX carries UVs for the atlas but names no texture; its one material now links
  `ClothedLightSkin.png`, by a relative path;
- of its nine takes, four are kept - `Idle`, `Walk`, `Run` and `Punch` - and the rest are dropped:
  two unnamed test actions and three the game has no use for;
- it was re-exported by Blender's FBX exporter, with each texture path written relative only, so
  the file names no directory on the machine that made it.

CC0 asks for no attribution; it is recorded here anyway, so the file's origin can be checked.
Like the rest of the model art, it is not covered by this repository's MIT licence.

## `Human.glb`

`Human.glb` is `Human.fbx` converted to binary glTF by Assimp, its texture embedded, written by
`./gradlew udeaWriteConvertedModels` on Linux x86_64 and committed. The game is given this file;
the build never converts, because Assimp's Windows and Linux builds convert the same `.fbx` to
floats that differ in their last bits (issue #244). `udeaVerifyConvertedModels`, on `check`, fails
when it is no longer `Human.fbx`'s conversion. It is the same CC0 art in another format.

## Why there are two copies of it in this repository

`moba/game/assets/models/human/` holds the identical files. An asset root belongs to one
game - `udea { assetRoots }` names it, and the packed `.udeapak` a game opens is built from its own
root alone - so Hollow reading moba's root would make one game's build depend on another game's
asset tree. The files are byte-for-byte the same and CC0, and `HumanAssetTest` in `hollow:game`
compares their SHA-256 against moba's, so the two cannot drift apart unnoticed.
