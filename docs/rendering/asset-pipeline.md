# Sprite asset pipeline

The converter packs a small, ordered list of PNG sprites into one Citro2D texture sheet. The default inputs are original geometric fixtures that exercise an opaque floor, a partially transparent overlay, two aligned animation frames, and a small transparent item. The current fixture atlas is a build-time prototype.

## Run the converter

The 3DS build uses this converter to generate the fixture sprite indices and embed the atlas in RomFS. The native Makefile accepts `PYTHON` and `TEX3DS` executable overrides. At runtime the factory test scene loads the sheet with nearest-neighbor filtering and uses geometric primitives if the sheet is unavailable.

Use Python 3.10+ and [devkitPro tex3ds v2.3.0](https://github.com/devkitPro/tex3ds/tree/37220c696e873812df41af62d1db0b20c66661fe). Put `tex3ds` on `PATH` or pass its path with `--tex3ds`. Run from the repository root in Bash.

```sh
python3 tools/build_sprite_atlas.py --output-dir build/assets/first
python3 tools/build_sprite_atlas.py --output-dir build/assets/second
cmp build/assets/first/sprites.t3x build/assets/second/sprites.t3x
cmp build/assets/first/sprites.h build/assets/second/sprites.h
```

Both comparisons should exit successfully. For repeatability between machines, record the converter build and its ImageMagick version along with the ordered inputs. To pack your own local inputs, append PNG paths in a fixed order. Use unique lowercase names, such as `floor.png` and `belt-0.png`. The header maps sprite names to Citro2D indices. Keep animation order in the caller and use those named indices rather than relying on atlas layout.

The initial Windows verification used tex3ds v2.3.0 with ImageMagick 7.1.2-36. Two runs produced byte-identical outputs. The five fixture sprites fit in a 32 × 64 sheet (8 KiB of texture data), and their decoded RGBA pixels matched the PNGs. On-console texture loading and frame time still need verification.

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
| Floor | `blocks/environment/stone1.png` |
| Ore overlay | `blocks/environment/ore-copper1.png` |
| Conveyor frames | `blocks/distribution/conveyors/conveyor-0-0.png`, `blocks/distribution/conveyors/conveyor-0-1.png` |
| Item | `items/item-copper.png` |

Before shipping a converted atlas, record each included asset's exact upstream revision, source path, attribution and applicable license, and include required notices and corresponding source with the distribution. The table is a starting point for that review.
