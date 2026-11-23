# Stock editor on 3DS

The experimental source-based editor uses the original Mindustry v160.8
`MapEditor`, `EditorTile`, `EditorTool`, and `MapIO`. The 3DS viewport
draws the atlas and block icons. The stock brush and undo stack modify the
same tile objects that the original map writer exports.

Hold L and R while starting the offline upstream build to open the editor.
Place an existing stock `.msav` map at `/3ds/min3dstry/editor-map.msav` on the
SD card. When this file is absent, the editor creates a 200-by-200 map with
the stock stone floor. Emulator or command-line launches can use
`--editor <source.msav> <export.msav>`.

| Control | Action |
| --- | --- |
| D-pad or Circle Pad | Move the tile cursor |
| R or L+R | Select the next or previous block in the stock editor palette |
| A / B | Stock pencil / stock eraser |
| X / Y | Undo / redo |
| SELECT | Export a new map to `/3ds/min3dstry/maps/` |
| START | Exit |

Exports appear in the 3DS Custom Game map list. The next free `Edited-N.msav`
filename protects existing maps. A configured command-line export also
requires an unused filename.

The JVM reference creates a new stock map, tests pencil, eraser, undo and
redo, then opens the original Glacier, edits a tile and reopens the export.
Use the usual pinned upstream runtime options with
`runStockMapEditorJvm --args='<stock-map.msav> <new-export.msav>'`.

Map selection and naming dialogs, map-info fields, tools beyond pencil and
eraser, brush size and drag input, team and rotation controls, and playtesting
from the editor are subsequent pieces of the stock editor port.
