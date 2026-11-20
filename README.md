<img src="assets/icon.svg" width="64" height="64" alt="">

# Biome Transition Generator

Mod pro **Minecraft 1.21.1 (NeoForge)**, který odstraňuje ostré hranice mezi biomy. Na obou stranách hranice vytvoří přechodovou oblast, ve které se postupně mísí stromy, tráva, květiny, keře a další prvky obou biomů. Do přechodu se rozlévá i povrch: písek z pouště do džungle, tráva z lesa do pouště.

Funguje automaticky pro **všechny biomy**, vanilla i modované. Nemusíte nic nastavovat pro jednotlivé dvojice biomů.

## Co mod umí

- **Detekce hranic**: hledá hranice biomů přímo ve worldgenu, i u chunků, které ještě nejsou vygenerované. Pro stejný seed dává vždy stejný výsledek. Funguje i v podzemí.
- **Míchání prvků (features)**: bere stromy, rostliny a dekorace sousedního biomu a generuje je v přechodové oblasti. Zachovává přitom jejich původní pravidla umístění (četnost, výška, vzácnost). Vlastní prvky biomu se ve stejném poměru ředí, takže se mix opravdu přelévá z biomu A do biomu B.
- **Plynulý přechod**: na samotné hranici je poměr 50/50 a směrem do biomu klesá k nule. Tvar přechodu se nastavuje křivkou (lineární, smoothstep, ease, vlastní, šumová).
- **Šířka podle klimatu**: podobné biomy (les a březový les) se mísí na široké oblasti, velmi odlišné (poušť a sníh) na užší.
- **Prolínání povrchu**: povrch sousedního biomu se rozlévá do přechodu v přirozených skvrnách, aby jeho rostliny měly na čem růst. Díky tomu rostou kaktusy v džungli u pouště.
- **Samostatný systém pro tekutiny**: vodní rostliny mají vlastní šířku a intenzitu. Nové jezera a prameny jsou ve výchozím stavu vypnuté a suchozemské prvky nikdy nevzniknou ve vodě.
- **Kontrola terénu**: hlídá povrchový blok, výšku, sklon, volný prostor nad prvkem a vodu.
- **Konfigurační GUI**: každé číselné nastavení má posuvník i pole pro přesnou hodnotu.
- **Ladicí nástroje**: příkaz `/btg` s vizualizací hranic, statistikami a výpisem, proč byl který prvek odmítnut.

## Instalace

1. Nainstalujte **NeoForge pro Minecraft 1.21.1** (verze 21.1.x).
2. Stáhněte `biometransitions-<verze>.jar` (sekce Releases, nebo si ho sestavte, viz níže).
3. Vložte jar do složky `mods` vaší instance Minecraftu.
4. Spusťte hru a **vytvořte nový svět** nebo prozkoumávejte nevygenerovaný terén. Mod mění jen chunky vygenerované po jeho přidání.

V singleplayeru stačí mít mod ve hře. Na serveru ho nainstalujte na server; doporučujeme ho mít i na klientech (konfigurační GUI).

## Rychlý start

```
/locate biome minecraft:desert     najděte hranici biomů a teleportujte se k ní
/btg show                          zobrazí hranice (červené stěny) a přechod (zeleno-žlutý)
/btg info                          ukáže sousední biomy, vzdálenost k hranici a poměr prvků
```

Nastavení: **Mods → Biome Transition Generator → Config**, nebo soubor `config/biometransitions.json`.

Podrobný návod k použití a popis všech nastavení: **[docs/NAVOD.md](docs/NAVOD.md)**

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
| Sběr a kategorizace prvků | `core/FeatureCatalog.java` |
| Kontrola terénu | `core/SurfaceRules.java` |
| Kategorie biomů | `core/BiomeClassifier.java` |
| Generování v přechodu | `worldgen/TransitionFeature.java` |
| Prolínání povrchu | `worldgen/SurfaceBlending.java` |
| Ředění vlastních prvků biomu | `worldgen/Suppression.java`, `mixin/BiomeFilterMixin.java` |
| Konfigurace a GUI | `config/BtgConfig.java`, `client/ConfigScreen.java` |
| Příkazy a vizualizace | `debug/` |

Mod přidá do každého biomu jeden vlastní prvek pro každou fázi generování, vždy na začátek seznamu. Pro každý chunk projde prvky sousedních biomů, pustí je jejich vlastními pravidly umístění a místo kontroly biomu použije poměr přechodu. Každý chunk generuje jen ze svých vlastních pozic, takže se nic negeneruje dvakrát.

## Známá omezení

- Mění jen nově generované chunky.
- Minimální rozestup prvků se hlídá jen v rámci jednoho chunku.
- Biomy, pro které není nastavený povrch (sníh, hory, oceán, řeka a modované biomy mimo známé kategorie), povrch neprolínají. Lze je doplnit v configu.
- Vzácné prvky (např. kaktusy) se v přechodu objevují úměrně tomu, jak vzácné jsou ve svém biomu.
