<img src="assets/icon.svg" width="128" height="128" alt="">

# Biome Transition Generator

Mod for **Minecraft 1.21.1 (NeoForge)**, The mod blends transitions between two or more biomes by generating features from both biomes within the transition zone, with their density gradually decreasing.
All transitions from one biome to another can be adjusted to ensure the best possible appearance.
____________________________________________________________________________

## What can the mod do?

- **Biome Feature mixing** up to 128 blocks away from the boundary
- **Biome color blending** up to 64x64 blocks
- **Biome surface blocks mixing** 
- **Cave biome mixing**
- **Almost everything can be customized**
- **Biome removal if it is too small**
- **Biome and terrain generator mods compatible**
____________________________________________________________________________

Mod pro **Minecraft 1.21.1 (NeoForge)**, Mod míchá přechody dvou a více biomů do sebe díky tomu, že generuje features z obou biomu v přechodové vzdálenosti s ubývající hustotou
Všechny přechody z jednoho biomu do jiného jdou upravit tak, aby vypadaly co nejlépe.


- **Míchání prvků (features)**: bere stromy, rostliny a dekorace sousedního biomu a generuje je v přechodové oblasti. Zachovává přitom jejich původní pravidla umístění (četnost, výška, vzácnost). Vlastní prvky biomu se ve stejném poměru ředí, takže se mix opravdu přelévá z biomu A do biomu B.
- **Plynulý přechod**: jedna křivka pokrývá celý přechod přes obě strany hranice; na hranici je poměr 50/50 (u jednosměrných dvojic 100 % souseda) a směrem do biomu klesá k nule. Tvar se nastavuje křivkou (lineární, smoothstep, ease, logaritmická, vlastní, šumová). Stromy mohou mít vlastní vzdálenost míchání.
- **Hustota a rozmanitost**: hustota v přechodu buď plynule přechází, nebo je průměrem obou biomů. Biom s mnoha druhy prvků nepřeválcuje souseda s několika.
- **Pravidla míchání**: biomy, které se nemísí vůbec (oceán, řeka, pláž), mísí se jen se svou skupinou (poušť, badlands), fungují jako bariéra (řeka), a pravidla pro dvojice skupin se směrem (les se rozlévá do plání, ne naopak) a vlastní vzdáleností.
- **Šířka podle podobnosti**: podobné biomy (dva lesy) se mísí na širší oblasti, odlišné klima zužuje přechod.
- **Nahrazování malých biomů**: malé oceány uprostřed říční sítě se změní na řeku. Malým biomům lze také jen potlačit vlastní prvky.
- **Míchání barev**: tráva, listí a voda se plynule prolínají na větší vzdálenost než ve vanille, bez výrazné ztráty výkonu. Funguje i se Sodiem.
- **Prolínání povrchu**: povrch sousedního biomu (i sněhová vrstva) se rozlévá do přechodu v přirozených skvrnách, aby jeho rostliny měly na čem růst. Má vlastní vzdálenost a u dvojic biomů vlastní směr, nezávislý na prvcích.
- **Samostatný systém pro tekutiny**: vodní rostliny mají vlastní šířku a intenzitu. Nové jezera a prameny jsou ve výchozím stavu vypnuté a suchozemské prvky nikdy nevzniknou ve vodě.
- **Kontrola terénu**: hlídá povrchový blok, výšku, sklon, volný prostor nad prvkem a vodu.
- **Struktury bez stromů**: stromy, rostliny a kameny se negenerují uvnitř struktur (domy a cesty ve vesnicích) ani v nastavitelné vzdálenosti kolem nich. Zbytek krajiny zůstává beze změny.
- **Konfigurační GUI**: posuvník i pole pro přesnou hodnotu, seznam biomů s vyhledáváním a výběrem skupiny, tooltip s vysvětlením, příkladem a výchozí hodnotou u každého nastavení, tlačítko pro návrat na výchozí hodnotu, zvýraznění změněných nastavení, rozbalovací seznam dvojic biomů.


V singleplayeru stačí mít mod ve hře. Na serveru ho nainstalujte na server; doporučujeme ho mít i na klientech (konfigurační GUI).

## Rychlý start

```
/locate biome minecraft:desert     najděte hranici biomů a teleportujte se k ní
/btg show                          zobrazí hranice (červené stěny) a přechod (zeleno-žlutý)
/btg info                          ukáže sousední biomy, vzdálenost k hranici a poměr prvků
```

Nastavení: **Mods → Biome Transition Generator → Config**, nebo soubor `config/biometransitions.json`.

Podrobný návod k použití a popis všech nastavení: **[docs/NAVOD.md](docs/NAVOD.md)**

Průzkum možností zrychlení generování (GPU, C2ME, OpenCL): [docs/OPTIMALIZACE.md](docs/OPTIMALIZACE.md)

## Sestavení ze zdrojového kódu

Je potřeba **JDK 21**.

```bash
./gradlew build        # sestaví mod a spustí unit testy, jar je v build/libs/
./gradlew runClient    # spustí hru s modem (složka runs/client)
./gradlew runServer    # spustí dedikovaný server (složka runs/server)
```

## Jak to funguje uvnitř

| Část | Soubor |
|---|---|
| Detekce hranic a cache | `core/BoundaryDetector.java` |
| Matematika přechodu (křivky, poměr, klima) | `core/TransitionMath.java`, `core/Transitions.java` |
| Pravidla míchání, hustota, rozmanitost | `core/Mixing.java`, `core/Density.java` |
| Malé biomy a jejich nahrazování | `core/BiomeReplacer.java`, `mixin/NoiseBasedChunkGeneratorMixin.java` |
| Míchání barev | `client/ColorBlending.java`, `mixin/ClientLevelMixin.java`, se Sodiem `mixin/compat/SodiumLevelSliceMixin.java` |
| Sběr a kategorizace prvků | `core/FeatureCatalog.java` |
| Kontrola terénu | `core/SurfaceRules.java` |
| Kategorie biomů | `core/BiomeClassifier.java` |
| Generování v přechodu | `worldgen/TransitionFeature.java` |
| Prolínání povrchu | `worldgen/SurfaceBlending.java` |
| Ředění vlastních prvků biomu | `worldgen/Suppression.java`, `mixin/BiomeFilterMixin.java` |
| Prvky mimo struktury | `worldgen/StructureExclusion.java` |
| Konfigurace a GUI | `config/BtgConfig.java`, `client/ConfigScreen.java` |
| Texty GUI (překlady) | `assets/biometransitions/lang/*.json` |
| Příkazy a vizualizace | `debug/` |

Mod přidá do každého biomu jeden vlastní prvek pro každou fázi generování, vždy na začátek seznamu. Pro každý chunk projde prvky sousedních biomů, pustí je jejich vlastními pravidly umístění a místo kontroly biomu použije poměr přechodu. Každý chunk generuje jen ze svých vlastních pozic, takže se nic negeneruje dvakrát.

## Známá omezení

- Mění jen nově generované chunky.
- Minimální rozestup prvků se hlídá jen v rámci jednoho chunku.
- Biomy, pro které není nastavený povrch (sníh, hory, oceán, řeka a modované biomy mimo známé skupiny), povrch neprolínají. Lze je doplnit v configu.
- Velikost malých biomů se měří ve výšce y = 64. `/locate biome` a umístění struktur používají původní (nenahrazené) biomy. Nahrazování biomů funguje pro generátory založené na šumu (všechny vanilla dimenze) a nebere ohled na seznam dimenzí.
- Vzácné prvky (např. kaktusy) se v přechodu objevují úměrně tomu, jak vzácné jsou ve svém biomu.
