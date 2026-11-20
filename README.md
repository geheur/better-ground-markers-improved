# rspaint
<video
  src="rspaint.mp4"
  autoplay loop muted playsinline controls
  width="718">
  <a href="rspaint.mp4">Watch the demo video</a>
</video>

Press the draw hotkey (`ctrl-b` by default) to turn on draw mode, then click and drag to doodle on the ground. `ctrl-e` turns on erase mode, and pressing a hotkey again turns its mode off. While either mode is on, left clicks draw or erase instead of moving your character.

If you prefer to hold the hotkeys, turn off Toggle draw and erase in the settings, and each mode stays on only while its key is held. Only one mode can be on at a time, so pressing the other key switches straight over.

The toolbar on the left side of the screen shows while you draw or erase. It has 20 color slots, which can be transparent, along with buttons to reset the palette and change the stroke size. The Rendering settings change how strokes look on your screen.

Lines stay on the floor you drew them on, so going up or down stairs shows the lines of that floor. Lines drawn before the plugin knew about floors show on the ground floor. When the GPU plugin draws the ground past the loading lines, your lines show out there too.

Undo (`ctrl-z`) and redo (`ctrl-shift-z`) work while you draw or erase and go back up to 100 changes, with a whole erase drag counting as one change. Your party sees your undos and redos as well, and Reset canvas clears the history.

## Party drawings
When you are in a party (join one with the Party plugin), the other members see your drawings near you as you draw, and you see theirs. Everyone has their own canvas, so you can only erase your own strokes.

Whenever someone joins, the party gets your newest 1000 strokes again, and anything older stays on your screen but drops off for everyone else.

The paintbrush icon in the side bar opens the RsPaint panel, which lists you and each party member. The switch next to a name hides or shows that player's drawings. Reset canvas permanently deletes your drawings in the area around you, for you and for the whole party, while your drawings in other places stay.

## Import and export drawings
The RsPaint panel can copy drawings to the clipboard as JSON text and read them back in. Export next to a party member's name copies their drawings, and next to your own name it copies your drawings in the area around you. Export shown drawings puts everyone whose switch is on into a single export.

To bring drawings in, copy an export and click Import drawings. The panel shows the stroke count and asks you to confirm, then adds the strokes to what you already have. Imported strokes become yours, so you can erase them, and your party sees them too.

An export is plain text, so you can keep it in a file or paste it into a message to share it.
