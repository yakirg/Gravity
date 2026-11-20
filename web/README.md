# Gravity - web version

Static browser port of the simulation (Three.js, no build step). Same `.gravity` JSON format as the desktop app.

## Run locally
    cd web
    python -m http.server 8000      # then open http://localhost:8000

## Host
Copy the `web` folder to any static host over HTTPS (GitHub Pages, Netlify, ...).

## Android
Open the hosted URL in Chrome, then menu > "Add to Home screen" / "Install app". It runs full-screen and works offline
after the first visit (launch-site imagery is fetched from Esri on first use and then cached).

Controls: Space pause, R restart, L launch now, C launch camera, O orbits, T table, A auto time scale, [ ] / 0-9 focus,
+/- or wheel/pinch zoom. Save/Load and the Masses/Rocket editors are in the top bar.
