# Artwork

The master files of the app's look. Edit the SVGs first and bring the Android icon in line after.

| File | What it is |
|---|---|
| [icon.svg](icon.svg) | The app's icon: a car seen from above between two walls. A unit on each wall sends signals to it, green on the left and amber on the right, the colours the app uses for the gaps. The dark green gradient around the lane is the icon's background |
| [icon-512.png](icon-512.png) | `icon.svg` at 512 × 512 px, the size that app stores ask for |
| [readme-header.svg](readme-header.svg) | The header of the project's [README](../../README.md): the icon, the name and the tagline. Its text is converted to outlines, so that it looks the same on every computer |
| [readme-header.src.svg](readme-header.src.svg) | The header with its text still as text, in the Fira Sans font. Edit this one and export the header from it |

## The Android icon

Android's [adaptive icon](../androidApp/src/main/res/mipmap-anydpi/ic_launcher.xml) has three layers, which are vector drawables in [androidApp/src/main/res/drawable/](../androidApp/src/main/res/drawable/):

- `ic_launcher_background.xml`: the dark green gradient
- `ic_launcher_foreground.xml`: the scene of `icon.svg`, without its rounded square. The group scales it to two thirds, so that the whole scene fits the circle that launchers cut out of the icon
- `ic_launcher_monochrome.xml`: the walls, the units, the car and the arcs in one colour, which Android 13 and later tints for themed icons

The drawables are translated from `icon.svg` by hand. When you change the icon, change all four files, and check the result at a small size: the seams of the walls, and the lenses of the units, disappear below about 40 px, and the colours of the arcs and of the car's edges have to carry the icon.

The header and the 512 px PNG are exported with Inkscape:

```bash
inkscape readme-header.src.svg --export-type=svg --export-text-to-path --export-plain-svg -o readme-header.svg
inkscape icon.svg --export-type=png -w 512 -h 512 -o icon-512.png
```
