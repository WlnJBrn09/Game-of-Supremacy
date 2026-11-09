# Verification

## Build and model checks

Built on Linux with JDK 21, Maven 3.9.9, and libGDX 1.13.1. `./mvnw clean verify` passes (the test environment supplies its HTTPS proxy/trust configuration externally; no environment-specific configuration is committed).

Five JUnit tests cover:

- All requested territory groups, seven continents, Pakistan/India and Portugal/Spain, Yakutsk/Irkutsk, and the Tasmania/Victoria merge.
- Reachability of all 71 territories, symmetric connections, no duplicate/self edges, 34 sea lanes, and India's land border with the Thailand region.
- Every polygon's triangulation and representative point-in-polygon hit tests.
- Explicit ownership samples in Belgium, Tanzania, Tasmania, all three parts of Chad, and both sides of the Mauritania split.
- Orthographic screen/world transforms, cursor-anchored zoom, zoom limits, and pan bounds.

## Desktop checks

Ran the packaged JAR on a real LWJGL3 window under Xvfb with Mesa software OpenGL. Exercised the app using X11 keyboard and pointer input, and inspected rendered screenshots:

- Overview, search and focus on Yakutsk, and its Irkutsk/Far East neighbours.
- Sea-lane and label toggles, help overlay, and continent filtering.
- Hawaii selection with its numbered US-West/Japan sea lanes and Pacific wrapping.
- Pan, reset, and resize from 1600×1000 to 1000×625 and back.

`board-preview.png` shows the final board rendered by libGDX. No browser or substitute HTML renderer is used. Audio is disabled because this board milestone has no sound. The static board redraws on input and resize rather than continuously consuming GPU time while idle.

Windows and macOS native libraries are included by the desktop backend, but those platforms were not executed here. The geography and additional sea links have not been playtested for conquest-game balance; no bonuses or gameplay rules are asserted yet.
