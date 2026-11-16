# 12. 3D-printed enclosure

- **Status:** Accepted
- **Date:** 2026-11-15

## Context

[ADR 0001](0001-prototype-hardware.md) put the first unit in a 120 × 80 × 50 mm IP65 box, with a USB-C panel-mount extension, and planned a 3D-printed enclosure to follow. The box stands 50 mm out from the passage wall, into a passage that is only 5–30 cm wider than the car.

The printed enclosure has to:

- stand out from the wall as little as possible, at most 30 mm, and be no wider than the battery needs; its height is free,
- hold on to a steel wall or gate with two 10 × 5 × 1 mm magnets in its back, spread along its length, and come off by hand for charging,
- carry everything the driver sees and the switch on its front: the sensor's opening, the switch, the LEDs and the buzzer's sound holes,
- bring the board's USB-C port flush with a wall, without the panel-mount extension,
- screw together, and hold the board and the sensor by their mounting holes,
- keep the sensor's chip right behind a wall no thicker than 2 mm, without a window ([ADR 0004](0004-vl53l1x-distance-sensor.md)), and the board's antenna 10 mm from the battery and the wiring ([ADR 0002](0002-ble-communication.md)).

The board and the sensor are wired with Dupont plugs on pin headers, which need 23 mm behind each of them. The deepest other part is the switch, 16 mm behind the front.

## Decision

The enclosure is an upright box, 39 × 125 × 29.5 mm, printed as a front tub and a back plate. [device/enclosure/README.md](../../device/enclosure/README.md) describes it, how to print it and how the parts go in.

- **Shape:** 35 mm wide inside, the battery's 34 mm and a little room. 25 mm deep inside, for the Dupont plugs, with a 2 mm front and a 2.5 mm back plate. The parts sit one above the other, except that the battery lies against the back plate behind the shallow ones: the buzzer, the switch and its LEDs, and the lower half of the sensor. The buzzer and the sensor lie across the box to save height.
- **Front:** from the top, the sensor's aperture, the switch between the green LED on its left and the red one on its right, and the buzzer's sound holes, to the right of the middle. The LEDs sit in printed collars with their domes 3.5 mm out of the front, without bezels.
- **Sensor:** the module lies across the box on two posts, its pins up and its chip 0.1 mm behind the front. Its holes are on narrow ears at its ends, which leave the corners beside its body free for the top two case screws' posts. The aperture is 6 × 4 mm at the chip and widens outward at 20° on each side, so that its sides stay out of the 27° field of view. No window.
- **Board:** component side toward the back, at the bottom, its antenna up. Its USB-C end rests against the bottom wall, which is as thick as the port stands past the board, 2 mm, so that the port's face is flush with the outside, in a slot with room to spare. Two M2 self-tapping screws, through its 2.5 mm holes as they come, cut their own thread in two posts.
- **Battery:** against the back plate, between two ribs on it, 10 mm above the antenna, with the buzzer, the switch and its LEDs, and the lower half of the sensor in front of it. The switch reaches to within 1 mm of the battery, so its wires leave its tabs sideways.
- **Sensor fixing:** printed pins through its two holes, melted over.
- **Buzzer fixing:** an M3 screw through its hole, cutting its own thread in a post. The hole is about 3 mm from the edge of the buzzer, too close for a post wide enough for a heat-set insert, so the post is cut flat on the buzzer's side.
- **Closing:** the back plate sits in a step around the back of the tub and is held by four countersunk M3 screws in heat-set inserts. The heads are flush with the back.
- **Mounting:** two 10 × 5 × 1 mm magnets glued flush into pockets in the back plate, a quarter of its length from each end. The pockets are 6 × 11 mm, 0.5 mm larger than the magnets all round, for the glue. On a wall that isn't steel, the unit hangs from a nail or a screw by a hook on top: a 3 mm tab flush with the back, standing 10 mm above the top, with a 5 mm hole, and braced underneath at 45° so that it prints without supports. Or the wall gets a steel plate or washers glued or screwed to it.
- **Unit numbers:** the tub comes in eight versions, one for each of the up to 8 units at a passage, with as many raised dots on top as the unit's number. The dots are 3 mm domes, 1 mm high, and fill a 4 × 2 grid from the front left, a row at a time, so that the units can be told apart by sight and by touch.
- **Material:** dark, opaque PETG.
- **Model:** written in build123d, in Python, in [device/enclosure/model.py](../../device/enclosure/model.py), and run with `just enclosure`. It models the parts the enclosure holds from their measured sizes and refuses to write the print files when one runs into the enclosure or another part, when the box gets deeper than 30 mm, or when the battery comes within 10 mm of the antenna. The same task draws the pictures of the enclosure in the docs from the model, with matplotlib, so that they follow it. build123d and matplotlib are in their own uv dependency group, `cad`, so that the other tasks and CI don't install them. The STL files for printing, eight tubs and the back plate, a STEP file of the tub without dots and the back plate, and the pictures are committed with the model.

## Alternatives considered

- **Lying along the passage, like the IP65 box.** The LEDs could face the approaching car from an end wall, but the USB-C would sit at an end where water and dust reach it, and a right-hand unit would again be a left-hand one turned upside down.
- **Every part upright, one above the other, the battery behind none of them.** Simpler to lay out, but 166 mm tall, against 125 mm.
- **The USB-C at the top.** Easier to plug in on the wall, but the port would face drips and dust, and the antenna would point down.
- **A clear window over the sensor.** It would seal the opening, but the VL53L1X then needs crosstalk calibration.
- **LEDs in bezels.** Neater, but dimmer from the side and deeper behind the front.
- **Wires soldered to the board and the sensor, as ADR 0001 has it.** The switch would set the depth instead, for a box about 25.5 mm deep, but the parts couldn't be unplugged.
- **A back tub with a front lid.** The parts on the front would be on a removable lid, with loose wires to the tub, and the USB-C slot would depend on how the lid sits.
- **Screws cutting their own thread for the back plate.** No inserts to buy, but the thread wears out after a few openings.
- **Magnets sealed into the plate during the print.** Cleaner, but the plastic between the magnet and the steel takes much of the 1 mm magnets' pull.
- **Keyhole slots for screws in the wall.** For walls that aren't steel, but they add holes and depth to the back plate; the hook on top does the same and leaves the unit removable.
- **A melted pin for the buzzer, as for the sensor.** No screw, but the buzzer could then come out only by cutting the pin.
- **Printed pins for the board.** No screws, but the board's 2.5 mm holes would need thin pins that hold less.
- **Pictures taken by hand in a CAD viewer or a slicer.** Easier to make look good, but they fall behind the model unless someone remembers to retake them.
- **One tub for every unit, told apart only in the app.** Fewer files to print from, but nothing on a unit would show which one it is.
- **OpenSCAD or FreeCAD.** OpenSCAD has weaker fillets and STEP export, and FreeCAD stores its models in binary files that git can't compare.

## Consequences

- The unit stands 29.5 mm out from the wall, instead of 50 mm, and is 125 mm tall.
- The same unit fits either wall, the right way up. This changes the face layout of ADR 0001, which is updated.
- An approaching driver sees the LEDs side-on. [ADR 0010](0010-device-firmware.md) wants them readable from the driver's seat; the domes standing out of the front are what makes that possible, and it is untested.
- On a steel gate, the antenna is about 20 mm in front of the steel, which can shorten the Bluetooth range. Untested.
- The magnets hold only on steel; elsewhere the unit hangs from its hook. With the hook, the unit is 135 mm tall.
- The pin headers' soldered ends have to be trimmed to 1.5 mm.
- The battery's lead is soldered to the back of the board's battery socket, which faces the bottom wall, where no plug fits.
- Dupont plugs can work loose on a gate that swings.
- The sensor comes out only by cutting its melted pins, and a new tub is then needed to fix it again.
- The model's sizes come from measurements of one set of parts, and the sensor's ears from a photo of it. A different board or module needs its sizes changed in the model, and the fit check then shows what no longer fits.
- The eight tubs' STL files, 1.3 to 2.1 MB each and 14 MB together, are committed, and are rewritten whenever the model changes.
- The enclosure hasn't been printed yet; its holes may need adjusting after the first print.
