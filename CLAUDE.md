# Biome Transition Generator

NeoForge 1.21.1 mod. Build with JDK 21: `./gradlew build` (compiles and runs the unit tests).

## Rules

- Every feature and every bug fix gets its own commit, with any docs, changelog or translation changes it needs in that same commit. Never bundle unrelated changes into one commit.
- User docs (`README.md`, `docs/`) are in Czech; keep them in sync with every user-visible change.
- GUI text lives in `src/main/resources/assets/biometransitions/lang/*.json`, never hardcoded in Java.
