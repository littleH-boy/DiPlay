# Smoother CarPlay on a slower head unit

DiPlay renders every frame the iPhone sends, and a head unit with a weak SoC, GPU or video decoder can fall behind. The picture then stutters, and touches feel late because every tap needs new frames that travel network → decoder → screen. This guide walks the display settings in the order that helps most, and shows how to check each step with the FPS counter instead of guessing. The settings live in DiPlay's **Settings** menu; in a session the same controls are in the quick menu's settings pages.

The measurements behind the trade-offs below were taken on a 2024 BYD Tang; see [Smooth wireless CarPlay](SMOOTH_WIRELESS.md). Other head units — including non-BYD ones — behave differently, which is exactly why each step ends with a check.

## 0. Measure first

Open **Settings → Diagnostics → FPS counter**. While CarPlay runs it shows frames shown per second, frames received per second, and the average decode time.

- `shown` close to `rx`: the head unit keeps up; stutter then comes from the link, not the decoder.
- `shown` well below `rx` while you scroll or move the map: the head unit cannot keep up. The steps below help.
- A high decode time (say over 33 ms, one 30 fps slot) points at the decoder specifically.

Also save a diagnostic report (**Settings → Diagnostics**). It records the video stats every 5 s and the decoder the head unit picked, which decides step 5.

Reproduce the stutter the way you normally see it — scrolling a music list, then the map — and note the numbers before changing anything.

## 1. Frame rate: 30 fps

**Settings → Display → Frame rate → 30 fps**, then **Apply and reconnect**. 30 fps is the default; if it was raised, put it back. Halving the frame rate halves the decode, transmission and rendering work per second and is the cheapest big step.

*Check:* `rx` drops to about 30; `shown` should sit just under it while scrolling.

## 2. Resolution: 80%, then 60%

**Settings → Display → Resolution → 80%**, reconnect, and try 60% if 80% was not enough. A smaller picture cuts decoder and GPU work on every frame, and the panel upscales it. INSTALL.md recommends the same for slower head units.

*Check:* the gap between `shown` and `rx` narrows; decode time falls. Text gets softer as the picture is scaled up — that is the trade.

## 3. Keep Efficient video (HEVC) off

**Settings → Advanced → Video and audio → Efficient video (HEVC)**. HEVC saves network bandwidth but decodes slower on many head-unit SoCs. Leave it off unless the wireless link itself is the bottleneck and the decoder is proven fast (decode time stays low with HEVC on).

## 4. Direct video output

**Settings → Advanced → Video and audio → Direct video output (experimental)**, off by default. It sends decoded frames straight to the system compositor through a `SurfaceView` instead of through DiPlay's own drawing — worth about one display refresh per frame, and it removes the app's GPU work entirely. Picture adjustments (brightness and similar) do not apply while it is on.

*Check:* `shown` converges on `rx`, and touches register sooner. Turn it off if the picture shows artifacts or freezes; a decoder whose output the app cannot composite cleanly needs the default path.

## 5. Decoder latency options

From the diagnostic report, find the decoder name in the video log lines:

- A **Qualcomm (`qti`) decoder** on Android 11+: try **Low-latency decoding (experimental)** in the same card. It asks for a decoder variant that releases each frame as soon as it is decoded instead of holding it behind later frames. Reported touch delay drops; some decoders refuse or misbehave, and DiPlay falls back on its own.
- An **MTK (MediaTek) decoder**: try **MTK decoder tuning (experimental)**, which enables the vendor low-latency/no-reorder modes with per-session fallback.
- Either way: if artifacts or freezes appear, turn the option off. These apply at the next connection.

## 6. Smooth video, last

**Settings → Advanced → Video and audio → Smooth video (experimental)** spreads the decoder's bunched output back onto the iPhone's frame grid. On the Tang it moved presented frames from 63% to 86% landing exactly one refresh apart — the most even picture available. The cost is deliberate: frames are held until their display time, adding roughly 90–140 ms before a touch shows. If your complaint is stutter, try it; if it is touch delay, it works against you. It also disables picture adjustments.

## 7. The link itself

With the built-in car hotspot (the default), keep the hotspot on **5 GHz** when the car offers it. Channel contention mostly affects Wi-Fi Direct, not the car hotspot — see [Smooth wireless CarPlay](SMOOTH_WIRELESS.md). If stutter persists after the steps above, a **USB data cable** removes the wireless link entirely and is the most robust connection a head unit can get.

## Reading the result

After each step, compare `shown` with `rx` in the same scene as your baseline. A head unit that has settled shows `shown` within a frame or two of `rx` and a decode time under one 30 fps slot. DiPlay also helps on its own: when the picture loses frames or decodes slowly for most of a 20-second stretch, it offers to lower the frame rate or resolution and reconnect — accepting applies exactly the steps above. If everything is already at 30 fps and 60% and the picture still stutters, save a diagnostic report; the remaining cause is likely the decoder or the link, and the report is what distinguishes them.
