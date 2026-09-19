# ground.glb: where it comes from

`ground.glb` is not third-party. `make_ground.py`, beside it, writes the whole file: an 80-metre
square and the texture on it, which the script paints from a fixed seed with numpy and Pillow, so
running it again writes the same picture. Nothing was downloaded to make it (issue #249).

It is part of this repository and under its licence.
