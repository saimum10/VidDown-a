# Branding

* `original-icon.png`: the logo supplied by the owner (orange flame with a download arrow, transparent background, 806x1080). This is the single source. (An earlier version of the logo on a rounded tile is in the git history.)
* Everything else is generated from it, deterministically: `pip install pillow numpy scipy && python3 scripts/make_icons.py`.
  Never edit the generated PNGs by hand; change the source or the script and regenerate.
* `preview.png`: contact sheet (adaptive icon under circle / squircle / square masks, legacy square and round icons, Android 13 monochrome layer, status-bar icon, logo on dark).
* `ic_launcher-playstore.png`: 512x512 store/listing icon.

## Where each file is used (all under `app/src/main/res/`)
| resource | used for |
|---|---|
| `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` + `mipmap-*/ic_launcher_foreground.png`, `ic_launcher_monochrome.png`, `values/ic_launcher_background.xml` | adaptive launcher icon (Android 8+) and themed icon (Android 13+) |
| `mipmap-*/ic_launcher.png`, `ic_launcher_round.png` | launcher icon on Android 7.x (minSdk is 24) |
| `drawable-*/ic_stat_viddown.png` | status-bar / notification small icon (white + alpha, 24dp). Use as `setSmallIcon(R.drawable.ic_stat_viddown)` for the download notifications (Phase 4) |
| `drawable-nodpi/viddown_logo.png` | in-app logo (transparent): header of the runtime-check screen now; Home, About and empty states later |

Background colour of the launcher icons: white `#FFFFFF`.
`logo.png` is the 256 px logo used at the top of the README.
