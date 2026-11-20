# Návod k použití a konfiguraci

Obsah:
1. [Jak mod funguje](#1-jak-mod-funguje)
2. [Postup při ladění přechodů](#2-postup-při-ladění-přechodů)
3. [Příkazy /btg](#3-příkazy-btg)
4. [Konfigurační GUI](#4-konfigurační-gui)
5. [Konfigurační soubor: všechna nastavení](#5-konfigurační-soubor-všechna-nastavení)
6. [Kategorie prvků a biomů](#6-kategorie-prvků-a-biomů)
7. [Recepty: časté úpravy](#7-recepty-časté-úpravy)
8. [Řešení problémů](#8-řešení-problémů)

---

## 1. Jak mod funguje

Když se dva biomy dotýkají, vznikne na **obou stranách** hranice přechodová oblast. V každém místě této oblasti mod spočítá **podíl souseda**, tedy kolik procent prvků má pocházet ze sousedního biomu:

- na samotné hranici **50 %** (vyrovnaný mix),
- směrem do biomu podíl klesá podle zvolené křivky,
- na okraji oblasti je **0 %** a biom vypadá normálně.

Podle tohoto podílu se:
1. **přidávají prvky souseda.** Jeho stromy, tráva a květiny se generují podle jejich vlastních pravidel, jen s pravděpodobností podle podílu.
2. **ředí vlastní prvky biomu.** Jeho stromy a rostliny se ve stejném poměru vynechávají, ale jen tehdy, když soused přináší něco stejné kategorie. Nikdy tak nevzniknou holá místa.
3. **mísí povrch.** Povrch souseda (písek, tráva, červený písek) se rozlévá v přirozených skvrnách. Rostliny souseda pak mají na čem růst.

Tekutiny (vodní rostliny, jezera, prameny) mají **samostatné nastavení**, takže změna hustoty vegetace neovlivní vodu.

Šířka oblasti se **přizpůsobuje klimatu**. Mod porovná teplotu a srážky obou biomů: podobné biomy dostanou plnou šířku, velmi odlišné užší.

> Mod mění jen **nově generované chunky**. Po každé změně nastavení prozkoumejte nový terén nebo vytvořte nový svět.

---

## 2. Postup při ladění přechodů

1. Vytvořte testovací svět s pevným seedem.
2. Najděte hranici: `/locate biome minecraft:desert` a teleportujte se k ní.
3. Zapněte vizualizaci: `/btg show`.
4. Postavte se do přechodu a zadejte `/btg info`.
5. Upravte nastavení v GUI, uložte a jděte do **nevygenerovaného** terénu (nebo vytvořte nový svět se stejným seedem).
6. Podívejte se do `/btg stats`, co se umístilo a co bylo odmítnuto.
7. Když nějaký prvek chybí, `/btg features <biom>` ukáže, jestli smí přecházet a proč případně ne.

---

## 3. Příkazy /btg

Vyžadují oprávnění operátora (úroveň 2).

| Příkaz | Co dělá |
|---|---|
| `/btg info` | Biom na vaší pozici a jeho kategorie, sousední biomy, vzdálenost k hranici, šířka přechodu (upravená podle klimatu), podíl souseda pro vegetaci a tekutiny a celkový poměr vlastní/cizí. |
| `/btg show [poloměr]` | Zapne nebo vypne vizualizaci kolem hráče (výchozí poloměr 24, rozsah 4–64). **Červené stěny** = hranice, **zelená → žlutá** = přechod vegetace (žlutější = víc prvků souseda), **modrá** = jen přechod tekutin. Nad hotbarem se zobrazuje nejbližší hranice a podíl souseda. |
| `/btg features [biom]` | Seznam všech prvků biomu po fázích generování: kategorie, typ a jestli smí přecházet (✔) nebo proč ne (✘). Bez argumentu bere biom na vaší pozici. |
| `/btg stats` | Počet pokusů, umístěných prvků podle kategorií, prolnutých chunků povrchu, zředěných vlastních prvků a důvody odmítnutí. |
| `/btg stats reset` | Vynuluje statistiky. |
| `/btg reload` | Znovu načte `config/biometransitions.json` (hodí se na serveru). |

---

## 4. Konfigurační GUI

Otevření: **hlavní menu → Mods → Biome Transition Generator → Config**.

- **Vlevo** je 10 sekcí, **vpravo** seznam nastavení s posuvníkem.
- **Čísla**: posuvník a pole pro přesnou hodnotu. Vedle je rozsah (min – max) a jednotka. Hodnota zadaná mimo rozsah se ořízne.
- **Zapnuto/vypnuto**: tlačítko `true` / `false`.
- **Volby** (křivka, tvar, režim): klikáním se přepíná mezi možnostmi.
- **Seznamy**: textové pole s hodnotami oddělenými čárkou, např. `#minecraft:dirt, minecraft:sand`.
- **Šedé „edit in config/biometransitions.json“**: tohle nastavení (mapy a složitější struktury) jde upravit jen v souboru.
- **Save** uloží soubor a hned nastavení použije. **Cancel** změny zahodí.

| Sekce v GUI | Část configu |
|---|---|
| General Settings | `general` |
| Boundary Detection | `detection` |
| Transition Zones | `transition.zone` |
| Feature Distribution | `transition.distribution` |
| Feature Selection | `transition.selection` + `features` |
| Biome Classification | `classification` (jen náhled pravidel) |
| Fluid Transitions | `transition.fluid` |
| Surface Compatibility | `featureDefaults` + `transition.surface` |
| Performance | `performance` |
| Debugging | `debug` |

Na dedikovaném serveru upravte soubor ručně a použijte `/btg reload`.

---

## 5. Konfigurační soubor: všechna nastavení

Soubor: `config/biometransitions.json`. Vytvoří se při prvním spuštění. Když je soubor poškozený, mod použije výchozí hodnoty a soubor nepřepíše. Když se ve nové verzi modu výrazně změní výchozí hodnoty, starý soubor se uloží jako `biometransitions.json.bak` a nahradí novým.

Zápis bloků, tekutin a biomů:
- `minecraft:sand`: konkrétní ID,
- `#minecraft:dirt`: tag,
- `#desert`: **kategorie biomu** (jen tam, kde se vybírají biomy, viz [kapitola 6](#6-kategorie-prvků-a-biomů)).

### `general`: obecné

| Klíč | Výchozí | Popis |
|---|---|---|
| `enabled` | `true` | Hlavní vypínač modu. |
| `vegetationTransitions` | `true` | Přechody vegetace a dekorací. |
| `fluidTransitions` | `true` | Přechody vodních rostlin a tekutin. Lze vypnout bez vypnutí vegetace. |
| `undergroundTransitions` | `true` | Hledat hranice i v podzemí (jeskynní biomy). |
| `dimensions` | `[]` | Seznam dimenzí, např. `["minecraft:overworld"]`. Prázdný = všechny. |

### `detection`: detekce hranic

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `maxDetectionDistance` | 48 | 8–128 bloků | Jak daleko se hledá sousední biom. Automaticky se zvětší, když je přechod širší. |
| `maxNeighbours` | 3 | 1–8 | Kolik nejbližších různých sousedních biomů se bere v úvahu. |
| `multiBiomeMode` | `ALL_NEIGHBOURS` | | Kde se potkají 3 a více biomů: `ALL_NEIGHBOURS` míchá všechny sousedy (celkový podíl max. 100 %), `NEAREST_PAIR` jen nejbližší. |

### `transition.zone`: přechodová oblast

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `width` | 64 | 0–128 bloků | Jak daleko do biomu sahají prvky souseda (na každé straně hranice). |
| `climateAdaptation` | 0.3 | 0–1 | Jak moc se šířka zúží u biomů s odlišným klimatem. 0 = stejná šířka všude, 1 = nejodlišnější dvojice dostanou jen 25 % šířky. |
| `intensity` | 1 | 0–2 | Síla přechodu. Nad 1 mohou prvky souseda u hranice převážit nad vlastními. |
| `falloff` | 1 | 0.1–4 | Exponent poklesu. Pod 1 = podíl zůstává vysoký dál od hranice, nad 1 = soustředí se u hranice. |
| `shape` | `ROUND` | | Tvar okraje: `ROUND` (kruhový), `SQUARE` (čtvercový), `DIAMOND` (kosočtverec). |

### `transition.distribution`: rozložení prvků

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `ownInfluence` | 1 | 0–4 | Váha vlastních prvků biomu. |
| `neighbourInfluence` | 1 | 0–4 | Váha prvků souseda. Při 3 a vlastní 1 je na hranici 75 % souseda. |
| `curve` | `EASE_OUT` | | Průběh podílu: `LINEAR`, `SMOOTHSTEP`, `EASE_IN`, `EASE_OUT`, `EASE_IN_OUT`, `CUSTOM`, `NOISE`. |
| `customCurve` | `[0, 0.1, 0.35, 0.75, 1]` | | Body vlastní křivky (pro `CUSTOM`), rovnoměrně od okraje oblasti (vlevo) k hranici (vpravo). |
| `smoothness` | 0.2 | 0–1 | Dodatečné vyhlazení křivky. |
| `randomVariation` | 0.1 | 0–1 | Náhodné kolísání podílu po sloupcích. |
| `noiseInfluence` | 0.3 | 0–1 | Šum, díky kterému je okraj přechodu nepravidelný a přirozený. U `NOISE` křivky dvojnásobný. |
| `noiseScale` | 32 | 4–256 bloků | Velikost „vln“ šumu. |
| `suppressNative` | `true` | | Ředit vlastní prvky biomu podle podílu souseda. Vypnuto = prvky souseda se jen přidávají navíc. |

Která křivka k čemu:
- `LINEAR`: rovnoměrný pokles,
- `EASE_OUT`: podíl drží vysoko a klesá až u okraje (výrazný přechod),
- `EASE_IN`: rychle klesá, soused je hlavně u hranice,
- `SMOOTHSTEP`: měkký začátek i konec.

### `transition.selection`: výběr prvků

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `frequency` | 1.5 | 0–4 | **Hustota** prvků souseda v přechodu. 1 = stejná hustota jako v jejich biomu (krát podíl), nad 1 = hustší. |
| `allowedCategories` | všechny | | Kategorie prvků, které smí přecházet. |
| `categoryFrequency` | `{"small_vegetation": 2.5}` | | Násobič hustoty pro kategorii. Malé rostliny (kaktus, cukrová třtina, dýně) jsou vzácné, proto mají víc pokusů. |
| `excludedFeatures` | `[]` | | ID prvků, které nikdy nepřecházejí, např. `minecraft:trees_plains`. |

### `transition.fluid`: tekutiny

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `width` | 8 | 0–64 bloků | Šířka přechodu pro vodní rostliny a tekutiny. |
| `intensity` | 1 | 0–2 | Síla přechodu tekutin. |
| `frequency` | 1 | 0–4 | Hustota vodních prvků. |
| `allowedFluids` | `["#minecraft:water"]` | | Tekutiny, ve kterých smí vznikat vodní prvky a které smí vytvářet jezera a prameny. Přidejte `#minecraft:lava` pro lávu. |
| `allowFluidBodies` | `false` | | Přenášet jezera a prameny. Vypnuto = žádné nové vodní plochy. |
| `allowLandFeaturesInFluid` | `false` | | Povolit suchozemské prvky ve vodě (stromy pod hladinou). |
| `shoreRadius` | 3 | 0–8 bloků | Do jaké vzdálenosti od vody platí bonus pro břehy. |
| `shoreFrequency` | 1.5 | 0–4 | Násobič hustoty břehových rostlin u vody. |
| `shoreCategories` | tráva, květiny, malé rostliny, keře | | Kategorie, na které se bonus pro břehy vztahuje. |

### `transition.surface`: prolínání povrchu

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `enabled` | `true` | | Zapnout prolínání povrchových bloků. |
| `intensity` | 1 | 0–2 | Jak velkou část přechodu povrch souseda pokryje. |
| `patchScale` | 10 | 2–64 bloků | Velikost skvrn. Menší = drobné ostrůvky, větší = velké plochy. |
| `depth` | 3 | 1–6 bloků | Do jaké hloubky se povrch mění (vrchní blok + bloky pod ním). |
| `blocks` | viz níže | | Biom nebo kategorie → `[vrchní blok, blok pod ním]`. Platí první shoda. |
| `replaceable` | hlína, písek, pískovec, terakota, štěrk | | Jen tyto bloky se smí přepsat. Kámen, rudy a stavby zůstanou nedotčené. |

Výchozí povrchy:

```json
"blocks": {
  "#desert":   ["minecraft:sand", "minecraft:sandstone"],
  "#beach":    ["minecraft:sand", "minecraft:sandstone"],
  "#badlands": ["minecraft:red_sand", "minecraft:terracotta"],
  "#forest":   ["minecraft:grass_block", "minecraft:dirt"],
  "#jungle":   ["minecraft:grass_block", "minecraft:dirt"]
}
```

(a dále `dense_forest`, `sparse_forest`, `plains`, `meadow`, `taiga`, `swamp`, `hills` s trávou.) Biom bez záznamu svůj povrch nešíří.

### `featureDefaults`: výchozí nastavení každého prvku

Platí pro každý prvek, pokud ho nepřepíše `categorySettings` nebo `featureOverrides`.

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `enabled` | `true` | | Smí prvek přecházet. |
| `frequency` | 1 | 0–4 | **Hustota** prvku (kolik ho bude). |
| `weight` | 1 | 0.01–10 | **Váha výběru**: kdo vyhraje, když se prvky přetahují o stejné místo nebo o limit na chunk. Nemění hustotu. |
| `maxAttempts` | 16 | 1–64 | Maximum pokusů o umístění na chunk. |
| `minSpacing` | 0 | 0–32 bloků | Minimální rozestup mezi kusy stejného prvku. |
| `maxSpacing` | 0 | 0–64 bloků | 0 = vypnuto. Jinak musí být každý další kus nejvýš takto daleko od předchozího (shlukování). |
| `allowedSurfaceBlocks` | `[]` | | Povolené bloky pod prvkem. Prázdné = jakýkoli. |
| `allowedBiomes` | `[]` | | V jakých biomech smí prvek vzniknout (ID nebo `#kategorie`). Prázdné = všude. |
| `minY` / `maxY` | -64 / 320 | | Povolený výškový rozsah. |
| `maxSlope` | 16 | 0–16 bloků | Maximální výškový rozdíl k sousedním sloupcům (16 = bez kontroly). |
| `requiredAirAbove` | 0 | 0–32 bloků | Kolik volných bloků musí být nad prvkem. |
| `layer` | `ANY` | | `ANY`, `SURFACE_ONLY` (jen povrch), `UNDERGROUND_ONLY` (jen podzemí). |
| `category` | `""` | | Ruční určení kategorie. Prázdné = automaticky. |

### `categorySettings` a `featureOverrides`: výjimky

Obojí obsahuje jen hodnoty, které se liší od `featureDefaults`. Pořadí platnosti je: `featureDefaults` → `categorySettings[kategorie]` → `featureOverrides[ID prvku]`.

Výchozí `categorySettings`:

```json
"categorySettings": {
  "trees":            { "allowedSurfaceBlocks": ["#minecraft:dirt"], "requiredAirAbove": 4, "maxSlope": 4, "minSpacing": 2 },
  "large_vegetation": { "requiredAirAbove": 3, "maxSlope": 4 },
  "rocks":            { "maxSlope": 6 }
}
```

Příklad `featureOverrides`:

```json
"featureOverrides": {
  "minecraft:patch_cactus_desert": { "frequency": 4 },
  "minecraft:trees_plains":        { "frequency": 0.5, "weight": 2, "minSpacing": 4 },
  "minecraft:patch_pumpkin":       { "enabled": false }
}
```

ID prvků zjistíte příkazem `/btg features <biom>`.

### `features`: pravidla pro prvky

| Klíč | Popis |
|---|---|
| `enabledSteps` | Fáze generování, ze kterých se prvky přenášejí. Výchozí: `LAKES`, `LOCAL_MODIFICATIONS`, `FLUID_SPRINGS`, `VEGETAL_DECORATION`. Rudy a podzemní stavby se nepřenášejí. |
| `excludedFeatureTypes` | Typy prvků, které nikdy nepřecházejí (rudy, fosilie, geody, dungeony, mrznutí povrchu, prvky Endu). |
| `typeCategories` | Typ prvku → kategorie (např. `minecraft:tree` → `trees`). |
| `keywordCategories` | Slovo v ID prvku → kategorie. Kontroluje se postupně, platí první shoda (např. `flower` → `flowers`). |

### `classification`: kategorie biomů

| Klíč | Popis |
|---|---|
| `overrides` | ID biomu → seznam kategorií. Má přednost před pravidly s tagy. |
| `tagRules` | Pravidla `{ "tags": [...], "categories": [...] }`. Biom musí mít **všechny** uvedené tagy. Biom dostane kategorie ze všech pravidel, která na něj sedí. |

### `performance`: výkon

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `cacheSize` | 8192 | 256–65536 | Velikost cache výsledků detekce. |
| `maxCandidatesPerChunk` | 1024 | 16–4096 | Max. kandidátů na umístění v jednom chunku. |
| `maxPlacementsPerChunk` | 160 | 1–1024 | Max. skutečně umístěných prvků v jednom chunku. |
| `undergroundSectionStride` | 2 | 1–8 sekcí | V podzemí kontrolovat každou n-tou sekci (16 bloků). Víc = rychlejší, méně přesné. |

### `debug`: ladění

| Klíč | Výchozí | Popis |
|---|---|---|
| `collectStats` | `true` | Sbírat statistiky pro `/btg stats`. |
| `logRejections` | `false` | Zapisovat do logu každé odmítnutí s důvodem a pozicí. Generuje hodně řádků. |

---

## 6. Kategorie prvků a biomů

**Kategorie prvků** (automaticky podle typu a ID):
`trees`, `large_vegetation`, `small_vegetation`, `flowers`, `grass`, `bushes`, `mushrooms`, `ground_cover`, `rocks`, `terrain_decorations`, `aquatic_vegetation`, `fluid`, `other`.

`aquatic_vegetation` a `fluid` patří do systému tekutin (sekce `transition.fluid`), ostatní do vegetace.

**Kategorie biomů** (podle tagů, lze přidávat vlastní):
`forest`, `dense_forest`, `sparse_forest`, `plains`, `meadow`, `mountain`, `hills`, `desert`, `badlands`, `swamp`, `jungle`, `taiga`, `snowy`, `beach`, `river`, `ocean`, `deep_ocean`, `underground`, `other`.

Kategorie biomů se používají v `transition.surface.blocks` a v `allowedBiomes` jako `#kategorie`. Biom bez kategorie je `other`.

---

## 7. Recepty: časté úpravy

**Výraznější a širší přechody**
- `transition.zone.width`: 96
- `transition.zone.intensity`: 1.3
- `transition.selection.frequency`: 2
- `transition.distribution.curve`: `EASE_OUT`

**Jemný, nenápadný přechod**
- `width` 24, `intensity` 0.7, `curve` `SMOOTHSTEP`, `frequency` 1.

**Stejná šířka pro všechny dvojice**
- `transition.zone.climateAdaptation`: 0

**Víc kaktusů a pouštních rostlin u pouště**
- `"categoryFrequency": { "small_vegetation": 4 }`, nebo jen pro kaktus:
  `"featureOverrides": { "minecraft:patch_cactus_desert": { "frequency": 4 } }`

**Méně stromů v přechodu do plání**
- `"categoryFrequency": { "trees": 0.5 }`

**Bez prolínání povrchu, jen rostliny**
- `transition.surface.enabled`: `false`

**Povrch pro modovaný biom**
1. Zařaďte biom do kategorie:
   `"classification": { "overrides": { "mujmod:red_dunes": ["desert"] } }`
2. Nebo mu dejte vlastní povrch:
   `"blocks": { "mujmod:red_dunes": ["minecraft:red_sand", "minecraft:red_sandstone"], ... }`

**Sněhové biomy se sněhovým povrchem**
- `"blocks": { "#snowy": ["minecraft:snow_block", "minecraft:dirt"], ... }`

**Přechod láva / podzemí**
- `"allowedFluids": ["#minecraft:water", "#minecraft:lava"]`, `"allowFluidBodies": true`

**Jen Overworld**
- `"dimensions": ["minecraft:overworld"]`

---

## 8. Řešení problémů

**Nevidím žádnou změnu.**
Mod mění jen nové chunky. Jděte do neprozkoumaného terénu nebo vytvořte nový svět. Zkontrolujte, že `general.enabled` je `true`.

**Prvek souseda se v přechodu neobjevuje.**
1. `/btg features <sousední biom>`: je u prvku ✔? Když je ✘, uvidíte důvod.
2. `/btg stats`: podívejte se na důvody odmítnutí.
   - `surface block not allowed`: prvek potřebuje jiný povrch (stromy hlínu, kaktusy písek). Zapněte nebo rozšiřte `transition.surface`.
   - `target biome already has it`: biom ten prvek už má, takže se nepřidává podruhé.
   - `feature declined the spot`: vlastní pravidla prvku místo odmítla (např. kaktus u vody).
   - `chunk placement limit`: zvyšte `performance.maxPlacementsPerChunk`.
3. Prvek může být jednoduše vzácný. Zvyšte jeho `frequency`.

**Ve světě je jen jeden biom.**
To mod nedělá: biomy nemění, jen čte. Zkontrolujte typ světa při vytváření (**Superflat** a **Single Biome** mají jeden biom).

**Generování je pomalé.**
Snižte `transition.zone.width`, `detection.maxNeighbours`, vypněte `general.undergroundTransitions` nebo zvyšte `performance.undergroundSectionStride`.

**Po aktualizaci modu se mi resetovalo nastavení.**
Nová verze změnila výchozí hodnoty. Vaše staré nastavení je v `config/biometransitions.json.bak`.
