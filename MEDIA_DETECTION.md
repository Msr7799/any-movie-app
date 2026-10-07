# Media detection flow — 2.1.0

The app does not hard-code a content provider. For pages you own or are authorized to inspect, the flow is:

1. Tavily/general web search returns a candidate watch/player page.
2. Server-side HTML discovery first checks for direct HLS/video URLs.
3. If the result is dynamic, Android opens it as a Web player.
4. WebView observes public media requests and forwards only safe playback headers
   (`Accept`, `Accept-Language`, `Origin`, `Referer`, `Range`, `User-Agent`) to `/api/v1/media`.
5. The server verifies HLS/video, analyzes the HLS master/media playlist, duration,
   variants, subtitle/audio renditions, encryption, and DRM indicators.
6. Non-DRM media is handed to Media3. Captured playback headers are reused for the
   HLS manifest, child playlists and media segments.
7. DRM-protected streams remain in the web player; the app does not attempt to bypass DRM.

## Web-player auto detection

After a dynamic result is opened, WebView attempts native `<video>.play()` and then a
small number of conservative interactions with controls that clearly look like Play,
Watch or Server controls. It waits up to roughly 20 seconds across several attempts while
network interception remains active. External top-level navigation is allowed only when
it looks like a player/embed/video/stream target; unrelated promotional navigation is blocked.

No cookies, authorization tokens or other sensitive headers are forwarded to the media API.
