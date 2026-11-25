# Experimentální optimalizace generování světa: průzkum

Stav: **výzkum, nic z toho není v modu zapnuté.** Tento dokument shrnuje, jestli a kde může Biome Transition Generator zrychlit generování světa, aniž by ohrozil kompatibilitu s dalšími mody. Platí zásada ze zadání: výkon nikdy na úkor kompatibility a stability generování.

## 1. Kde mod tráví čas

| Část | Kdy běží | Cena | Už optimalizováno |
|---|---|---|---|
| Detekce hranic (`BoundaryDetector`) | jednou na sekci chunku | vzorkování biomů v okolí chunku (mřížka 4×4 bloky) | cache vzorků i hotových map, chunky s jediným biomem končí hned, za globální vzdáleností míchání se vzorkuje jen každá druhá buňka, v hloubce řidší mřížka |
| Míchání prvků (`TransitionFeature`) | jednou na chunk a fázi | běh pravidel umístění sousedních prvků | levné odmítnutí před drahou částí, limity kandidátů a umístění na chunk |
| Ředění vlastních prvků (`Suppression`) | u každého vanilla prvku, který projde filtrem biomu | dotaz na podíl souseda | mapa hranic je v cache, mimo přechody končí brzy |
| Prvky mimo struktury (`StructureExclusion`) | jednou na chunk + test pozice | 17 × 17 dotazů na starty struktur | výsledek v cache na chunk, test pozice je jen pár obdélníků |
| Míchání barev (`ColorBlending`) | na klientovi při stavbě sekcí | až 33 × 33 vzorků na uzel mřížky | uzel se počítá jednou pro 16 sloupců a ukládá se |

Nejdražší je **vzorkování biomů** (`BiomeSource.getNoiseBiome`, tj. vanilla klimatický šum) a **běh cizích prvků**. Obojí je kód Minecraftu nebo jiných modů, ne výpočet tohoto modu.

## 2. Akcelerace na GPU

**Závěr: nevyplatí se, nedoporučuji.**

- **Hranice biomů**: vstupem jsou biomy z `BiomeSource`, což je strom density funkcí v Javě, který mohou měnit datapacky i mody (Terralith, Tectonic, Lithostitched...). Přenést ho na GPU znamená přepsat celý noise router, a to je přesně ta část, kterou mody pro generování terénu mění. Bez toho by GPU dostávalo jen hotovou mřížku biomů, tedy jednoduchý výpočet vzdáleností na zhruba 2–5 tisících buněk na chunk. To trvá na CPU mikrosekundy, kdežto přenos dat a synchronizace s GPU stojí stovky mikrosekund až milisekundy.
- **Míchání barev**: běží ve vláknech, která staví meshe sekcí (vanilla i Sodium). Uzly se ukládají do cache a sdílí je 16 sloupců, takže na sekci se nově spočítá jen pár desítek uzlů. Čtení zpět z GPU by stavbu sekce zdrželo víc, než kolik by ušetřilo. Kdyby to bylo potřeba, levnější cesta je na CPU: místo vážené sumy 33 × 33 vzorků použít oddělitelný průměr (box blur po řádcích a sloupcích), jako to dělá Sodium.
- **Hromadné vzorkování biomů**: tady by GPU dávalo smysl jen tehdy, kdyby celý noise router už běžel na GPU (viz C2ME OpenCL níže). Mod sám by to neměl dělat.

## 3. C2ME a OpenCL moduly

**C2ME** paralelizuje generování chunků (víc vláken, jiné plánování úloh) a zachovává přitom vanilla pravidla přístupu ke chunkům.

- Mod je na paralelní generování připravený: všechny sdílené cache jsou `ConcurrentHashMap` nebo synchronizované mapy, statistiky jsou `LongAdder` a konfigurace se vyměňuje atomicky (`volatile`). Žádný stav se nesdílí přes `ThreadLocal`, který by C2ME mohl rozbít.
- Prvky mimo struktury čtou starty struktur v okruhu 8 chunků přes `WorldGenRegion`. Na tom stojí i vanilla umisťování struktur, takže to C2ME musí zachovat.
- Mixin `NoiseBasedChunkGeneratorMixin` obaluje jen `fillBiomesFromNoise` (`@WrapOperation`, ne `@Redirect`), takže se dá řetězit s jinými mody. C2ME tuto metodu podle dostupných informací nenahrazuje. **Netestováno**, ověřit v reálné instanci.
- Rizika: velikosti cache (`performance.cacheSize`, `structures.cacheSize`) jsou nastavené pro výchozí počet vláken. Při mnoha vláknech C2ME se může zvýšit počet vymazání celé cache. Řešení je zvětšit cache, nebo v budoucnu přejít na skutečné LRU.

**C2ME OpenCL Acceleration Module** zrychluje vyhodnocování density funkcí (šum terénu) na GPU. S tímto modem se nepřekrývá: mod biomy jen čte přes `BiomeSource`. Pokud modul zrychlí i klimatický sampler, zrychlí se tím i detekce hranic. Konflikt nehrozí, protože mod do noise routeru nezasahuje.

## 4. Vlastní optimalizace generování

Kandidáti seřazení podle poměru přínosu a rizika:

1. **Sdílení vzorků biomů s vanillou** (přínos střední, riziko nízké): vanilla při `BIOMES` uloží biomy chunku do sekcí. Pro chunky, které už tento stav mají, by detekce hranic mohla číst biomy ze `ChunkAccess` místo nového vzorkování. Komplikace: sousední chunky ve stavu `BIOMES` nemusí existovat a výsledek musí zůstat deterministický, ať se chunk generuje z kterékoli strany. Fallback na vzorkování proto musí dávat totožné hodnoty, což platí, protože jde o stejný zdroj.
2. **Skutečné LRU místo mazání celé cache** (přínos nízký až střední, riziko nízké): odstraní občasné „studené“ chunky po vymazání cache.
3. **Rychlejší míchání barev přes oddělitelný průměr** (přínos střední na klientovi, riziko nízké): O(r) místo O(r²) na uzel. Váha „stan“ (tent) je oddělitelná, takže výsledek zůstane stejný.
4. **Přeskočení `Suppression` pro chunky bez přechodu** (přínos nízký): mapa hranic už se cachuje, zbývá jen cena dotazu do cache na každý prvek.
5. **Paralelní výpočet uvnitř chunku** (nedoporučuji): vlákna generování jsou už vytížená paralelně po chunkách (vanilla i C2ME). Další paralelismus uvnitř chunku by jen přidal režii a riziko.

Co mod dělat **nebude**: nahrazovat chunk generátor, měnit pořadí fází generování ani `ChunkPyramid`, měnit umisťování struktur nebo noise router. To jsou místa, na která sahají C2ME, mody terénu a mody struktur. Patří sem i Create Aeronautics, který spoléhá na vanilla chování chunků, bloků a struktur. Všechny zásahy tohoto modu jsou malé a řetězitelné: jeden `@Inject` do filtru biomu, jeden `@WrapOperation` při plnění biomů a vlastní prvek na začátku seznamu prvků každého biomu.

## 5. Doporučení

- GPU akceleraci neimplementovat. Přínos by byl záporný a riziko kompatibility vysoké.
- S C2ME a C2ME OpenCL nedělat žádnou zvláštní integraci. Mod je s nimi kompatibilní z principu, ale je potřeba ho otestovat v reálné instanci (C2ME + tento mod + Create Aeronautics, nový svět, `/btg stats`, log bez chyb).
- Jako první skutečnou optimalizaci zvážit bod 4.3 (míchání barev) a bod 4.2 (LRU). Bod 4.1 jen s měřením před a po (např. Spark profiler na stejném seedu a stejné trase).
