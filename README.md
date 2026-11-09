# rspaint
<video
  src="rspaint.mp4"
  autoplay loop muted playsinline controls
  width="718">
  <a href="rspaint.mp4">Watch the demo video</a>
</video>

Press the draw hotkey (default `ctrl-b`) to turn draw mode on, then click and drag the mouse to doodle on the ground. Press `ctrl-e` to turn erase mode on. Press the hotkey again to turn the mode off.

Change colors by clicking the menu on the left side of the screen.

To hold the hotkeys instead, turn off Toggle draw and erase in the settings. Then the mode is on only while you hold its hotkey. Draw mode and erase mode cannot both be on. If you press the erase key in draw mode, erase mode replaces draw mode. The opposite is also true.

While you draw or erase, a left click draws or erases, and does not move your character.

A line shows only on the floor where you drew it. If you go up or down stairs, you see the lines of the new floor. Lines that you drew before floors existed in the plugin show on the ground floor.

If the GPU plugin loads the ground past the loading lines, lines show on that ground too.

To undo your last line or erase, press the undo key (default `ctrl-z`) while you draw or erase. To redo it, press the redo key (default `ctrl-shift-z`). One erase drag undoes as one change. Undo goes back 100 changes. If you are in a party, your party members see the undo and the redo too. Reset canvas deletes the undo history.

The toolbar has 20 color slots, a button that resets the palette, and buttons that change the stroke size. The toolbar shows only while you draw or erase. Colors can be transparent. Use the Rendering settings to change how strokes look on your screen.

## Party drawings
If you are in a party, your party members see your drawings near you while you draw. You see their drawings too. Each member has a separate canvas, and you can erase only your own strokes. To join a party, use the Party plugin.

When a member joins the party, all members get your newest 1000 strokes again. Your older strokes stay on your screen, but your party members do not see them after that.

To open the RsPaint panel, click the paintbrush icon in the side bar. The panel shows you and each party member.

- To hide or show the drawings of a player, click the switch next to the name.
- To delete your drawings in the area around you, click Reset canvas. This deletes them for you and for your party members. Your drawings in other places stay. You cannot undo this.

## Import and export drawings
Export copies drawings to the clipboard as JSON text. Import reads that text from the clipboard. Use the RsPaint panel.

- To copy your drawings in the area around you, or the drawings of a party member, click Export next to the name.
- To copy the drawings of all players whose switch is on, click Export shown drawings. All of the drawings go into one export.
- To add drawings from the clipboard, copy an export, then click Import drawings. The panel shows the number of strokes and asks you to confirm. If you are in a party, your party members see the imported drawings too.

Imported drawings become your strokes, so you can erase them. Import does not delete the drawings that you already have.

To keep an export, paste it into a text file. To share it, paste it into a message.
