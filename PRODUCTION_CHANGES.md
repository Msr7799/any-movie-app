# Any Movie 2.3 — production handoff

- Website-aligned dark cinema visual system based on `Msr7799/Movies_Player`.
- Animated user sidebar with Dashboard, Public Catalog, Library, Watchlist, Recent and Ratings.
- Recent-watch history appears directly inside the sidebar.
- Dashboard includes server/TMDB readiness, personal stats, Continue Watching, Watchlist, Recent, Public Catalog, and TMDB search.
- Personal Firebase library is separated from the MongoDB-backed public catalog.
- TMDB search saves to the user's personal library only; only admins may create/update shared catalog records.
- Search supports both the live legacy 1.4 request contract and the new 2.3 contract during rollout.
- Network failures expose actionable status/code/request ID and are logged to Firebase Analytics without recording the search query.
- Default app appearance is dark to match the cinema website identity.
