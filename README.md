# rspaint
Hold the hotkey (default `ctrl-b`) and click and drag the mouse to doodle on the ground. Hold `ctrl-e` to erase.

Change colors by clicking the menu on the left side of the screen.

To use the hotkeys without holding them, turn on Toggle draw and erase in the settings. Then press a hotkey once to turn its mode on. Press it again to turn the mode off.

A line shows only on the floor where you drew it. Lines that you drew before floors existed in the plugin show on the ground floor.

To undo your last line or erase, press `ctrl-z` while you draw or erase. To redo it, press `ctrl-y`. Undo goes back 100 changes.

The toolbar has 20 color slots, a button that resets the palette, and buttons that change the stroke size. Colors can be transparent. Use the Rendering settings to change how strokes look on your screen.

## Party drawings
If you are in a party, your party members see your drawings near you while you draw. You see their drawings too. You can erase only your own strokes. To join a party, use the Party plugin.

To open the RsPaint panel, click the paintbrush icon in the side bar. The panel shows you and each party member.

- To hide or show the drawings of a player, click the switch next to the name.
- To delete your drawings in the area around you, click Reset canvas. Your drawings in other places stay. You cannot undo this.

## Import and export drawings
Export copies drawings to the clipboard as JSON text. Import reads that text from the clipboard. Use the RsPaint panel.

- To copy your drawings in the area around you, or the drawings of a party member, click Export next to the name.
- To copy the drawings of all players whose switch is on, click Export shown drawings.
- To add drawings from the clipboard, copy an export, then click Import drawings. The panel shows the number of strokes and asks you to confirm.

Imported drawings become your strokes, so you can erase them. Import does not delete the drawings that you already have.
