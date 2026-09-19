# The nature models: licence and attribution

The `.glb` files in this folder are from **Kenney's Nature Kit (2.1)**, released under **CC0 1.0
Universal** (public domain): <https://creativecommons.org/publicdomain/zero/1.0/>. They are the
trees, stones, logs, fence and ground cover of Hollow's forest clearing (issue #249).

- Source page: <https://kenney.nl/assets/nature-kit>, which states "License: Creative Commons CC0".
- Download: <https://kenney.nl/media/pages/assets/nature-kit/37ac38a37b-1677698939/kenney_nature-kit.zip>,
  SHA-256 `fa7974a0d342bfe63c38664ba9f8ec1a4aab8ea25f099bdc56870e33588c4d9d`. Its `License.txt`
  reads: *"License: (Creative Commons Zero, CC0) ... This content is free to use in personal,
  educational and commercial projects. Support us by crediting Kenney or www.kenney.nl (this is not
  mandatory)"*.
- Each file is `Models/GLTF format/<name>.glb` in that download. The SHA-256 of each file as
  published, before the change below:

| File | SHA-256 as published |
|---|---|
| `tree_pineTallA_detailed.glb` | `9ba6337460e718b8baf165b23c02f39455e3d5757b1911505410c46bc88a86b5` |
| `tree_pineTallB_detailed.glb` | `a26b4434e1592ec1a83f364a54688bb34c48d9521bfccbe63f0cc37b381c4696` |
| `tree_pineRoundC.glb` | `29080a4935bae6b4ab29066f22c99c58f88b23ce4e2f79b4dcbb5e76de73f69d` |
| `tree_pineRoundE.glb` | `39178f614e1b4b2340a5db89cde410e897027a67e28f39ba95359c522d114f23` |
| `tree_oak.glb` | `d7fd8773674928c50c11b66d12c636d49bdcc15a8b1c7fbb98e6f63a3439a3f3` |
| `tree_default.glb` | `562d29638c902de3c7bee465d3a53bb77117efbc392ae04ed894faf6b5dc691d` |
| `tree_detailed.glb` | `c041daf2f0fb1d49e4325227cbcd58667adbe51e9b55e8c1f0a94b74cc521b3b` |
| `tree_fat.glb` | `84b262c5dda3a91ac6c95f9d8b23a0ebbb1951d678c0e1375d3e32623cc43ee2` |
| `stone_largeA.glb` | `fe486a13775e833b1aca99413a89e013f300841de80f42ecd44ceaf43720f17b` |
| `stone_largeC.glb` | `f78e984f04f2c9d3a242d42b1c8f65b8a095b9ccf9f74ac55a97b7cdd1c4668c` |
| `stone_tallA.glb` | `3070bd574104abce19f5e3c82c917cb7f52801720377beba7aad76058408d5db` |
| `stone_tallE.glb` | `626eb1f842d8bdfe4c2be86dc7b469ebceadaed2402871c21a90324a0231a318` |
| `stone_smallA.glb` | `b48e731e6c4b453e8e5e8caace6edc18da40bc09874d831b7914e840fc853ae4` |
| `stone_smallC.glb` | `dfc8129bc9290155c524aeaf3b845aa68eb72580afccd5b969d598294d147136` |
| `stump_round.glb` | `e9b0f385f0ef98493ef716a055aa51e0b99efb8445279be7bb8bd83329a4c4af` |
| `log_large.glb` | `e7a92369062d491e5a31a163d390e07db04ee573605bbfa84c00660d18896b4c` |
| `fence_simple.glb` | `ecaf6c29532aa9fd305a8ef71df769d60748bd797f2eb1c63bdefc4edb8062a7` |
| `grass_large.glb` | `af49a595624abca7249824eeefb944d4baf974abeeb5b16110d175da3563d6cd` |
| `grass.glb` | `260e41d3e5f2472492ed7b475c5b92a30b13ce2bad408535b5ff50574d4575e7` |
| `plant_bush.glb` | `ae7b1beb39e242b13f5f29e3ec23ef21034b814f82297b8aa00a9bf4e1b09590` |
| `plant_bushLarge.glb` | `10e1d9fbf29d96d1d1a56fb2ce25ae66537505101759229afe01ae22c6c45e02` |
| `mushroom_redGroup.glb` | `29f02df0589dd2b09e8d2aff6a4662394dac5cfcf1fec701640dcbd6127e0802` |
| `flower_yellowA.glb` | `8a3b08cd2ca411c21f9c581d5bda651d78b13c50cba45ad8fa0389398ead6d1d` |
| `flower_purpleA.glb` | `f6fc34c96a03420a74fe36d4c2d0ec88f15204fcad99cd1416c3c4ecb22e48c4` |

## What was changed

`prepare.py`, beside them, is the whole change, and it writes these files from the published ones:

- the published materials are `KHR_materials_unlit`: a flat colour no light or shadow reaches. The
  clearing is lit by a sun that casts shadows, so the extension is removed and every material is a
  rough (0.9), non-metallic PBR surface;
- the kit's mint-and-orange palette becomes forest colours, one per material name, listed in the
  script.

Geometry, node transforms and binary buffers are unchanged. To reproduce, run
`python3 prepare.py "<download>/Models/GLTF format"` in this folder.

CC0 asks for no attribution; it is recorded here anyway, so each file's origin can be checked. Like
the rest of the model art, these files are not covered by this repository's MIT licence.
