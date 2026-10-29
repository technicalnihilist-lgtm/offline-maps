# Contributing

1. Keep the module boundaries: `core/*` must never depend on `app`.
2. Pure logic belongs in `core:common` or a core module and must come with a JVM unit test.
3. Do not add a paid API key or a proprietary dataset to core offline features.
4. Run `./gradlew test` before opening a PR; CI runs it too.
5. New user-visible strings that describe coverage must stay honest (no "all roads", no
   "every building has a 3D model").
