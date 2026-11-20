# Sprite asset pipeline

Status: **emulator-tested** (`ATLAS loaded`, textured screenshots and parity verified in Azahar `2126.2`; physical Old/New 3DS frame-time verification pending).

The source port reads the official Mindustry v160.8 atlas using Arc's `TextureAtlasData`. The importer inventories the official sprites and packs a first set for Glacier and the factory scenario. The platform prototype has a separate sheet of geometric test fixtures.

## Import upstream sprites

Download `assets.jar` and `server-release.jar` from the [official Mindustry v160.8 release](https://github.com/Anuken/Mindustry/releases/tag/v160.8). The importer verifies both SHA-256 digests. The server JAR supplies the pinned Arc reader. The artwork and region metadata come from the asset JAR entries `assets/sprites/sprites.aatls` and `assets/sprites/sprites{,2,3,4}.png`.

```sh
python3 tools/build_upstream_sprite_atlas.py \
  --assets /path/to/assets.jar --server /path/to/server-release.jar \
  --output-dir build/assets/upstream-factory
python3 tests/tools/verify_upstream_sprite_atlas.py build/assets/upstream-factory
```

Use JDK 17+, Python 3.10+, and tex3ds v2.3.0. The pixel check also uses ImageMagick 7. The output directory must be new. Append explicit upstream region names to build a different page.

- `regions.tsv` inventories 5,200 regions from four pages in Arc load order. It records page coordinates, packed dimensions, trim offsets and original dimensions, rotation, and nine-patch splits and pads. Original dimensions of zero in the Arc data indicate that the source region has no separate trim record. When names repeat, the exporter uses the last region, matching Arc's lookup.
- `png/` contains the selected packed regions at their upstream size. The importer compares every exported RGBA texel against the region in the release atlas. Use the trim metadata when positioning a cropped region.
- `sprites.t3x` and `sprites.h` contain the Citro2D page and named indices. The first 39 regions include ice, ice-snow, snow and copper-ore variants, all 20 conveyor frames, the router, mechanical drill layers, the shard core and its Sharded team overlay, and the copper item. They fit in a 256 × 512 RGBA8 page (512 KiB). Host verification decoded 61,740 texels from the T3X. Every alpha channel and every RGB channel at nonzero alpha matched. Tex3ds clears RGB under fully transparent texels. Two builds with the same tools produced identical outputs.

Renderer integration is tracked in [issue #38](https://github.com/levkeveli/Min3DStry/issues/38). The page has been verified on the host.

## Build the platform test fixtures

The 3DS build uses this converter to generate the fixture sprite indices and embed the atlas in RomFS. The native Makefile accepts `PYTHON` and `TEX3DS` executable overrides. At runtime the factory test scene loads the sheet with nearest-neighbor filtering and uses geometric primitives if the sheet is unavailable.

Use Python 3.10+ and [devkitPro tex3ds v2.3.0](https://github.com/devkitPro/tex3ds/tree/37220c696e873812df41af62d1db0b20c66661fe). Put `tex3ds` on `PATH` or pass its path with `--tex3ds`. Run from the repository root in Bash.

```sh
python3 tools/build_sprite_atlas.py --output-dir build/assets/first
python3 tools/build_sprite_atlas.py --output-dir build/assets/second
cmp build/assets/first/sprites.t3x build/assets/second/sprites.t3x
cmp build/assets/first/sprites.h build/assets/second/sprites.h
```

Both comparisons should exit successfully. For repeatability between machines, record the converter build and its ImageMagick version along with the ordered inputs. To pack your own local inputs, append PNG paths in a fixed order. Use unique lowercase names, such as `floor.png` and `belt-0.png`. The header maps sprite names to Citro2D indices. Keep animation order in the caller and use those named indices rather than relying on atlas layout.

The initial Windows verification used tex3ds v2.3.0 with ImageMagick 7.1.2-36. Two runs produced byte-identical outputs. The five fixture sprites fit in a 32 × 64 sheet (8 KiB of texture data), and their decoded RGBA pixels matched the PNGs. Azahar `2126.2` now reports `ATLAS loaded` on both models and renders textured screenshots; physical Old/New 3DS frame time remains pending. See [azahar-atlas-evidence.md](azahar-atlas-evidence.md).

## Image quality and texture budget

- Keep source dimensions and transparent margins so an overlay or animation frame retains its alignment. The converter uses RGBA8 and a one-pixel edge border around each sprite. It does not resample, trim, or generate mipmaps.
- Set nearest-neighbor filtering and align sprites to screen pixels at the default zoom when integrating the sheet. Compare the same scene while stationary and while panning.
- The converter prints base-level texture bytes, calculated from the packed dimensions, including unused space and borders. Each page has a 1 MiB cap (the cost of 512 × 512 RGBA8). Track the combined footprint of simultaneously loaded pages and measure free memory and frame time on Original 3DS hardware. Keep the frequently drawn world sprites together where possible to limit texture switches.
- If the measured footprint is too high, compare lower-cost formats on opaque terrain first. Treat tiny sprites, crisp outlines, and partially transparent edges as the visual reference when deciding which pages to keep in RGBA8.

## Source and license audit

The five PNGs in `tests/assets/` were created for Min3DStry from simple geometric patterns and are provided under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). The external converter is licensed under GPLv3-or-later according to its [source notice](https://github.com/devkitPro/tex3ds/blob/37220c696e873812df41af62d1db0b20c66661fe/source/tex3ds.cpp) and is invoked as a separate build-time executable.

For a future upstream asset batch, this audit examined candidate paths at Mindustry revision [`19a0f40`](https://github.com/Anuken/Mindustry/tree/19a0f40d44514f4802a8b79770456e7e32e34da0). Its [repository license](https://github.com/Anuken/Mindustry/blob/19a0f40d44514f4802a8b79770456e7e32e34da0/LICENSE) is GPLv3. The tree has no separate license file in the candidate sprite directories. This records the repository-level license and source locations. Per-file authorship and any third-party terms still need review before distribution.

| Category | Candidate path under `core/assets-raw/sprites/` |
| --- | --- |
| Floor | `blocks/environment/ice1.png`, `blocks/environment/ice-snow1.png`, `blocks/environment/snow1.png` and their numbered variants |
| Ore overlay | `blocks/environment/ore-copper1.png` |
| Conveyor frames | `blocks/distribution/conveyors/conveyor-*-*.png` |
| Router | `blocks/distribution/router.png` |
| Drill | `blocks/drills/mechanical-drill.png`, `blocks/drills/mechanical-drill-rotator.png`, `blocks/drills/mechanical-drill-top.png` |
| Shard core | `blocks/storage/core-shard.png`, `blocks/storage/core-shard-team.png` (the latter feeds the upstream generated `core-shard-team-sharded` region) |
| Item | `items/item-copper.png` |

Upstream [Generators.java](https://github.com/Anuken/Mindustry/blob/19a0f40d44514f4802a8b79770456e7e32e34da0/tools/src/mindustry/tools/Generators.java) builds the team palette variant. [Arc's atlas reader](https://github.com/Anuken/Arc/blob/7445105cd2683daf5b9ac7033ebb07265d3e814d/arc-core/src/arc/graphics/g2d/TextureAtlas.java) interprets the packed metadata. The importer uses local JAR inputs, and generated artwork stays in the local build directory.

Before shipping a converted atlas, record each included asset's exact upstream revision, source path, attribution and applicable license, and include required notices and corresponding source with the distribution. The table is a starting point for that review.
