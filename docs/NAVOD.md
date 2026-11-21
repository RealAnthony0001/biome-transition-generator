# Návod k použití a konfiguraci

Obsah:
1. [Jak mod funguje](#1-jak-mod-funguje)
2. [Postup při ladění přechodů](#2-postup-při-ladění-přechodů)
3. [Příkazy /btg](#3-příkazy-btg)
4. [Konfigurační GUI](#4-konfigurační-gui)
5. [Zápis biomů: ID, tagy, skupiny](#5-zápis-biomů-id-tagy-skupiny)
6. [Konfigurační soubor: všechna nastavení](#6-konfigurační-soubor-všechna-nastavení)
7. [Výchozí pravidla míchání](#7-výchozí-pravidla-míchání)
8. [Recepty: časté úpravy](#8-recepty-časté-úpravy)
9. [Řešení problémů](#9-řešení-problémů)

---

## 1. Jak mod funguje

Když se dva biomy dotýkají, vznikne u hranice přechodová oblast. V každém jejím místě mod spočítá **podíl souseda**, tedy kolik procent prvků má pocházet ze sousedního biomu:

- na samotné hranici **50 %** (vyrovnaný mix),
- směrem do biomu podíl klesá podle zvolené křivky (**Mixing Distribution**),
- ve vzdálenosti **Biome Feature Mixing Distance** je **0 %** a biom vypadá normálně.

Podle tohoto podílu se:
1. **přidávají prvky souseda.** Jeho stromy, tráva a květiny se generují podle jejich vlastních pravidel, jen s pravděpodobností podle podílu.
2. **ředí vlastní prvky biomu.** Jeho stromy a rostliny se ve stejném poměru vynechávají, ale jen když soused přináší něco stejné kategorie. Nikdy tak nevzniknou holá místa.
3. **mísí povrch.** Povrch souseda (písek, tráva...) se rozlévá v přirozených skvrnách, aby rostliny souseda měly na čem růst.

**Celkové množství** prvků v přechodu určuje **Feature Density**: buď plynulý přechod hustoty z jednoho biomu do druhého (`GRADUAL`), nebo průměr obou biomů v celé oblasti (`AVERAGE`). Rozložení (čí prvky kde převažují) a hustota (kolik jich je) jsou dvě samostatná nastavení.

Které biomy se smí mísit, řeší **pravidla míchání**: globální seznamy (biomy, které se nemísí vůbec, mísí se jen samy se sebou, fungují jako bariéra...) a pravidla pro konkrétní **dvojice skupin biomů** (např. les se rozlévá do plání, ale pláně ne do lesa).

Mod umí také:
- **nahrazovat malé biomy**: malý ostrůvek oceánu uprostřed říční sítě se změní na řeku,
- **potlačit prvky malých biomů**: biom si nechá ID, ale porostou v něm prvky okolních biomů,
- **míchat barvy biomů** (tráva, listí, voda) na větší vzdálenost než vanilla.

> Mod mění jen **nově generované chunky**. Po každé změně nastavení prozkoumejte nový terén nebo vytvořte nový svět.

---

## 2. Postup při ladění přechodů

1. Vytvořte testovací svět s pevným seedem.
2. Najděte hranici: `/locate biome minecraft:forest` a teleportujte se k ní.
3. Zapněte vizualizaci: `/btg show`.
4. Postavte se do přechodu a zadejte `/btg info`. Uvidíte, kteří sousedé se smí mísit a s jakou šířkou.
5. Upravte nastavení v GUI, uložte a jděte do **nevygenerovaného** terénu (nebo vytvořte nový svět se stejným seedem).
6. Podívejte se do `/btg stats`, co se umístilo a co bylo odmítnuto.
7. Když nějaký prvek chybí, `/btg features <biom>` ukáže, jestli smí přecházet a proč případně ne.

Pro rychlou vizuální kontrolu zapněte **Debug → Extreme Preset**: biomy mimo přechody nebudou mít žádné vlastní prvky, takže přechody budou jasně vidět. Jen pro testovací svět.

---

## 3. Příkazy /btg

Vyžadují oprávnění operátora (úroveň 2).

| Příkaz | Co dělá |
|---|---|
| `/btg info` | Biom na vaší pozici a jeho skupiny, sousední biomy, vzdálenost k hranici, šířka a křivka přechodu podle pravidel míchání (nebo „does not mix into this biome“), podíl souseda pro vegetaci a tekutiny a celkový poměr vlastní/cizí. |
| `/btg show [poloměr]` | Zapne nebo vypne vizualizaci kolem hráče (výchozí 24, rozsah 4–64). **Červené stěny** = hranice, **zelená → žlutá** = přechod vegetace (žlutější = víc prvků souseda), **modrá** = jen přechod tekutin. Nad hotbarem se zobrazuje nejbližší hranice a podíl souseda. |
| `/btg features [biom]` | Seznam všech prvků biomu po fázích generování: kategorie, typ a jestli smí přecházet (✔) nebo proč ne (✘). |
| `/btg stats` | Pokusy, umístěné prvky podle kategorií, prolnutý povrch, zředěné vlastní prvky, nahrazené malé biomy a důvody odmítnutí. |
| `/btg stats reset` | Vynuluje statistiky. |
| `/btg reload` | Znovu načte `config/biometransitions.json` (hodí se na serveru). |

---

## 4. Konfigurační GUI

Otevření: **hlavní menu → Mods → Biome Transition Generator → Config**.

- **Vlevo** je 10 sekcí, **vpravo** nastavení s posuvníkem.
- **Najeďte myší** na název nebo ovládací prvek: tooltip vysvětlí, co nastavení dělá, uvede příklad a u posuvníků význam levého a pravého konce. U voleb ukáže popis aktuální možnosti.
- **Čísla**: posuvník a pole pro přesnou hodnotu. Některé posuvníky mají speciální levý konec, např. `-1` = **Overlapping Detection** nebo **Global**.
- **Volby**: klikáním se přepíná mezi možnostmi. U dvojic biomů je navíc možnost **Global** = převzít globální nastavení.
- **Seznamy**: hodnoty oddělené čárkou, např. `ocean, river, #minecraft:is_beach`.
- **Save** uloží soubor a hned nastavení použije. **Cancel** změny zahodí.

| Sekce v GUI | Část configu |
|---|---|
| General | `general` |
| Boundary Detection | `detection` |
| Biome Mixing | `mixing` + oddělená část **Surface Compatibility & Surface Block Blending** (`featureDefaults`, `mixing.surface`) |
| Biome Boundary Mixing | `pairs` (rozbalovací seznam dvojic, tlačítka **+ Add pair** a **✕**) |
| Biome Replacing | `replacing` |
| Biome Color Blending | `general.colorBlendDistance` + `colors` |
| Biome Classification | `classification` (náhled pravidel) |
| Performance | `performance` |
| Advanced | `advanced` + `features` |
| Debug | `debug` |

**Max Biome Color Blending** je v General i v Biome Color Blending. Je to jedno nastavení, obě místa mění stejnou hodnotu.

Na dedikovaném serveru upravte soubor ručně a použijte `/btg reload`.

---

## 5. Zápis biomů: ID, tagy, skupiny

Všude, kde se vybírají biomy (seznamy míchání, dvojice, nahrazování, barvy, `allowedBiomes`), platí stejný zápis:

| Zápis | Význam |
|---|---|
| `minecraft:river` | konkrétní biom (ID) |
| `#minecraft:is_ocean` | biomový tag |
| `ocean` | **skupina** biomů (viz níže) |
| `desert+badlands` | víc skupin dohromady jako jedna skupina |
| `all` | všechny biomy |
| `surface` / `underground` | povrchové / podzemní (jeskynní) biomy |

**Skupiny biomů** se přiřazují automaticky podle tagů (sekce `classification`):
`forest`, `dense_forest`, `sparse_forest`, `plains`, `savanna`, `meadow`, `mountain`, `hills`, `desert`, `badlands`, `swamp`, `jungle`, `taiga`, `snowy`, `beach` (včetně kamenitého pobřeží), `river`, `ocean`, `deep_ocean`, `underground`, `other`. Jeden biom může patřit do více skupin (temný les je `dense_forest` i `forest`).

**Kategorie prvků** (automaticky podle typu a ID prvku):
`trees`, `large_vegetation`, `small_vegetation`, `flowers`, `grass`, `bushes`, `mushrooms`, `ground_cover`, `rocks`, `terrain_decorations`, `aquatic_vegetation`, `fluid`, `other`.
Pro **Feature Density** a **Variety Balancing** patří do „Trees and Foliage“: stromy, velké a malé rostliny, květiny, tráva, keře, houby a vodní rostliny. Ostatní jsou „Other“.

---

## 6. Konfigurační soubor: všechna nastavení

Soubor: `config/biometransitions.json`. Vytvoří se při prvním spuštění. Když je poškozený, mod použije výchozí hodnoty a soubor nepřepíše. Když se ve nové verzi modu změní struktura, starý soubor se uloží jako `biometransitions.json.bak` a nahradí novým.

### `general`: obecné

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `enabled` | `true` | | Hlavní vypínač modu. |
| `mixingDistance` | 64 | 0–128 bloků | **Biome Feature Mixing Distance**: jak daleko od hranice sahají prvky souseda. Dvojice mohou mít vlastní. |
| `surfaceMixing` | `true` | | Míchání povrchových bloků. |
| `biomeReplacing` | `true` | | Nahrazování malých biomů (sekce `replacing`). |
| `colorBlendDistance` | 32 | 0–64 bloků | **Max Biome Color Blending**. 0 = vanilla míchání barev. |
| `dimensions` | `[]` | | Dimenze, ve kterých mod míchá prvky, např. `["minecraft:overworld"]`. Prázdné = všechny. |
| `mixSurfaceAndUnderground` | `false` | | Míchat povrchové a podzemní skupiny biomů (les a svěží jeskyně pod ním). |

### `detection`: detekce hranic a malých biomů

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `overlapMultiplier` | 0.4 | 0.1–2 | Pro **Overlapping Detection**: biom je malý, když ho celý pokryjí přechodové oblasti sousedů (vzdálenost míchání × tato hodnota). Nižší = za malé se považují jen velmi úzké biomy. Nemění vzdálenost generování prvků. |
| `smallBiomeDetection` | `true` | | Měřit velikost oblastí biomů v quartech (quart = 4 × 4 bloky, jednotka, ve které Minecraft vzorkuje biomy). Potřebuje ho Biome Replacing. |

### `mixing`: globální pravidla míchání

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `density.treesAndFoliage` | `GRADUAL` | | Hustota stromů a rostlin. `AVERAGE` = celý přechod má průměr obou biomů (les 100 stromů, pláně 40 → 70). `GRADUAL` = hustota se mění plynule od hranice. |
| `density.other` | `GRADUAL` | | Totéž pro kameny, skvrny povrchu a ostatní prvky. |
| `distribution` | | | Rozložení prvků v přechodu, viz tabulka níže. |
| `varietyBalancing` | `FOLIAGE_ONLY` | | **Feature Variety Balancing**: když má soused víc druhů prvků než biom, každý jeho druh se úměrně zředí, aby nepřevážil. `DISABLED`, `ENABLED`, `FOLIAGE_ONLY` (jen stromy a rostliny). |
| `similarDistanceMultiplier` | 1.5 | 0.25–4 | Násobič vzdálenosti míchání pro biomy stejné skupiny (dva lesy, dvě tajgy). Neplatí pro dvojice s vlastní vzdáleností. |
| `intersectionPolicy` | `RESPECT_RULES` | | Kde se potkají 3 a více biomů: `RESPECT_RULES` = každý soused podle svých pravidel, `DO_NOT_BLEND` = tam se nemísí nic. |
| `noBlend` | `ocean, river, beach` | | Biomy, které se nemísí s ničím. |
| `blendOnlyWithSelf` | `desert+badlands` | | Biomy, které se mísí jen v rámci své skupiny. Spojení `+` dělá ze skupin jednu: poušť a badlands se mísí spolu, s ničím jiným. |
| `noSurfaceBlend` | `[]` | | Biomy, jejichž povrch se nemísí (ani nepřijímá cizí). Jejich prvky se mísí normálně. |
| `barriers` | `river` | | Biomy fungující jako bariéra: prvky se nemísí mezi biomy na opačných stranách (stromy z lesa nepřeskočí řeku do plání). |
| `surface` | | | Prolínání povrchu, viz níže. |

**`mixing.distribution`: rozložení prvků (Mixing Distribution)**

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `curve` | `EASE_OUT` | | Průběh podílu souseda: `LINEAR`, `SMOOTHSTEP`, `EASE_IN`, `EASE_OUT`, `EASE_IN_OUT`, `CUSTOM`, `NOISE`. |
| `customCurve` | `[0, 0.1, 0.35, 0.75, 1]` | | Body křivky `CUSTOM`, od okraje oblasti k hranici. |
| `intensity` | 1 | 0–2 | Síla přechodu. Nad 1 soused u hranice převládne. |
| `falloff` | 1 | 0.1–4 | Exponent poklesu. Pod 1 = soused sahá daleko v plné síle, nad 1 = mizí dřív. |
| `shape` | `ROUND` | | Měření vzdálenosti: `ROUND`, `SQUARE`, `DIAMOND`. |
| `ownInfluence` / `neighbourInfluence` | 1 / 1 | 0–4 | Váhy vlastních a cizích prvků. Při 1 a 3 je na hranici 75 % souseda. |
| `smoothness` | 0.2 | 0–1 | Dodatečné vyhlazení křivky. |
| `randomVariation` | 0.1 | 0–1 | Náhodné kolísání po sloupcích. |
| `noiseInfluence` | 0.3 | 0–1 | Šum, díky kterému je okraj přechodu nepravidelný. |
| `noiseScale` | 32 | 4–256 bloků | Velikost „vln“ šumu. |
| `suppressNative` | `true` | | Ředit vlastní prvky biomu podle podílu souseda. Vypnuto = prvky souseda se jen přidávají. |

**`mixing.surface`: prolínání povrchu** (zapíná se `general.surfaceMixing`)

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `intensity` | 1 | 0–2 | Jak velkou část přechodu povrch souseda pokryje. |
| `patchScale` | 10 | 2–64 bloků | Velikost skvrn. |
| `depth` | 3 | 1–6 bloků | Do jaké hloubky se povrch mění. |
| `blocks` | viz níže | | Biom/skupina → `[vrchní blok, blok pod ním]`. Platí první shoda. |
| `replaceable` | hlína, písek, pískovec, terakota, štěrk | | Jen tyto bloky se smí přepsat. |

```json
"blocks": {
  "desert":   ["minecraft:sand", "minecraft:sandstone"],
  "beach":    ["minecraft:sand", "minecraft:sandstone"],
  "badlands": ["minecraft:red_sand", "minecraft:terracotta"],
  "forest":   ["minecraft:grass_block", "minecraft:dirt"]
}
```
(a dále `dense_forest`, `sparse_forest`, `plains`, `meadow`, `jungle`, `taiga`, `swamp`, `hills`, `savanna` s trávou). Biom bez záznamu svůj povrch nešíří.

### `featureDefaults`, `categorySettings`, `featureOverrides`: Surface Compatibility

Pravidla, která musí splnit každý přimíchaný prvek. Pořadí platnosti: `featureDefaults` → `categorySettings[kategorie]` → `featureOverrides[ID prvku]`; poslední dvě obsahují jen odlišné hodnoty.

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `enabled` | `true` | | Smí prvek přecházet. |
| `frequency` | 1 | 0–4 | Násobič hustoty prvku. |
| `weight` | 1 | 0.01–10 | Kdo vyhraje, když se prvky přetahují o místo nebo o limit na chunk. Nemění hustotu. |
| `maxAttempts` | 16 | 1–64 | Pokusů o umístění na chunk. |
| `minSpacing` / `maxSpacing` | 0 / 0 | bloky | Minimální rozestup / maximální vzdálenost od předchozího kusu (shluky). 0 = vypnuto. |
| `allowedSurfaceBlocks` | `[]` | | Povolené bloky pod prvkem. Prázdné = jakýkoli. |
| `allowedBiomes` | `[]` | | Biomy, do kterých smí prvek přejít. Prázdné = všude. |
| `minY` / `maxY` | -64 / 320 | | Výškový rozsah. |
| `maxSlope` | 16 | 0–16 | Max. výškový rozdíl k sousedním sloupcům (16 = bez kontroly). |
| `requiredAirAbove` | 0 | 0–32 | Volné bloky nad prvkem. |
| `layer` | `ANY` | | `ANY`, `SURFACE_ONLY`, `UNDERGROUND_ONLY`. |
| `category` | `""` | | Ruční určení kategorie. |

Výchozí `categorySettings`: stromy potřebují hlínu, 4 bloky volna a sklon max. 4; velké rostliny 3 bloky volna; kameny sklon max. 6. ID prvků zjistíte příkazem `/btg features <biom>`.

### `pairs`: Biome Boundary Mixing

Seznam dvojic skupin biomů. Pro dvojici biomů platí **první** dvojice, která na ně sedí (v libovolném pořadí). Dvojice, které nejsou v seznamu, se řídí globálními pravidly z `mixing`.

| Klíč | Výchozí | Popis |
|---|---|---|
| `a`, `b` | | Skupina A a B (ID, tag nebo skupina). |
| `mode` | `BOTH` | `DO_NOT_BLEND`, `A_INTO_B` (prvky A se rozlévají do B), `B_INTO_A`, `BOTH`. |
| `distance` | -1 | Vlastní vzdálenost míchání v blocích. -1 = **Global** (`general.mixingDistance`). |
| `treesAndFoliageDensity`, `otherDensity` | `null` | Přepsání Feature Density. `null` = Global. |
| `curve` | `null` | Přepsání křivky rozložení. `null` = Global. |
| `varietyBalancing` | `null` | Přepsání Variety Balancing. `null` = Global. |
| `surfaceMixing` | `true` | Míchat povrch pro tuto dvojici. |

Globální seznamy (`noBlend`, `blendOnlyWithSelf`, bariéry), `intersectionPolicy` a `similarDistanceMultiplier` platí vždy a dvojice je nepřepisují.

### `replacing`: Biome Replacing

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `replaceBiomes` | `ocean` | | **Small Biomes with Biome ID Replacement**: biomy, jejichž malé oblasti se nahradí jiným biomem. |
| `replaceMinSize` | 306 | -1–4096 quartů | Oblasti menší než tato velikost se nahradí. 306 quartů ≈ 70 × 70 bloků. -1 = **Overlapping Detection**. |
| `changeInto` | `CUSTOM` | | `NEAREST_SAME_GROUP` = nejbližší biom stejné skupiny, `NEAREST_SIMILAR_GROUP` = sousední biom s nejpodobnějším klimatem, `CUSTOM` = podle mapování. |
| `customMapping` | `ocean-river` | | Dvojice `zdroj-cíl` oddělené čárkou, např. `ocean-river,forest-plains`. Hledá se nejbližší biom cíle, i ve vzdálenějším okolí (až 128 bloků). Když se nenajde, biom zůstane. |
| `suppressFeatureBiomes` | `[]` | | **Small Biomes with Feature Suppression**: biom si nechá ID, ale v malé oblasti negeneruje vlastní prvky. Místo nich rostou prvky okolních biomů podle pravidel míchání. |
| `suppressMinSize` | -1 | -1–4096 quartů | Velikostní limit pro potlačení prvků. -1 = Overlapping Detection. |
| `neverReplace` | `[]` | | Biomy, které se nikdy nenahradí. Má nejvyšší prioritu. |

Oblast je souvislá plocha quartů, které odpovídají stejné položce seznamu (např. všechny druhy oceánů dohromady). Velikost se měří ve výšce y = 64 a hlubší vrstvy se řídí tímto výsledkem. Rozhodnutí je stejné, ať se oblast poprvé vygeneruje z kterékoli strany. Nahrazený biom vidí celá hra: barvy, povrch, příkazy, moby. `/locate biome` ale používá původní biomy a struktury (např. ruiny pod vodou) se umisťují podle původního biomu.

### `colors`: Biome Color Blending

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `sameAsMixingDistance` | `true` | | Barvy se mísí stejně daleko jako prvky, nejvýš však Max Biome Color Blending. Vypnuto = vždy Max Biome Color Blending. |
| `reducedBiomes` | `[]` | | Biomy s kratší vzdáleností míchání barev (např. bažiny, aby jejich tmavá voda zůstala ostrá). |
| `reducedDistance` | 4 | 0–64 bloků | Vzdálenost pro `reducedBiomes`. |

Míchání barev běží na klientovi a čte nejvýš 13 × 13 vzorků na blok, takže je i při 64 blocích levnější než nejvyšší vanilla nastavení.

### `classification`: skupiny biomů

| Klíč | Popis |
|---|---|
| `overrides` | ID biomu → seznam skupin. Má přednost před tagy. |
| `tagRules` | Pravidla `{ "tags": [...], "categories": [...] }`. Biom musí mít **všechny** tagy a dostane skupiny ze všech pravidel, která na něj sedí. |

### `features`: pravidla pro prvky (Advanced)

| Klíč | Popis |
|---|---|
| `enabledSteps` | Fáze generování, ze kterých se prvky přenášejí. |
| `excludedFeatureTypes` | Typy prvků, které nikdy nepřecházejí ani se neředí (rudy, fosilie, geody, dungeony...). |
| `typeCategories` | Typ prvku → kategorie. |
| `keywordCategories` | Slovo v ID prvku → kategorie (první shoda). |

### `performance`: výkon

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `cacheSize` | 8192 | 256–65536 | Velikost cache výsledků detekce hranic. |
| `maxCandidatesPerChunk` | 1024 | 16–4096 | Max. kandidátů na umístění v chunku a fázi. |
| `maxPlacementsPerChunk` | 160 | 1–1024 | Max. umístěných prvků v chunku a fázi. |
| `undergroundSectionStride` | 2 | 1–8 sekcí | V podzemí kontrolovat každou n-tou sekci (16 bloků). |

### `advanced`: pokročilé

| Klíč | Výchozí | Rozsah | Popis |
|---|---|---|---|
| `preventFeatureOverlap` | `false` | | **Prevent Feature Block Overlap**: přimíchaný prvek nesmí stát na pevném bloku ani přímo na jiném prvku (kámen na kameni, rostlina na listí). Koruny stromů se překrývat smí. Vypnuto = žádné kontroly navíc. |
| `climateAdaptation` | 0.3 | 0–1 | Zúžení přechodu u biomů s odlišným klimatem. 0 = stejná šířka všude. |
| `maxNeighbours` | 3 | 1–8 | Kolik různých sousedních biomů se v jednom místě mísí. |
| `vegetationTransitions` / `fluidTransitions` | `true` | | Míchání suchozemských / vodních prvků. |
| `undergroundTransitions` | `true` | | Hledat hranice i podzemních biomů. |
| `selection.frequency` | 1.5 | 0–4 | Násobič hustoty přimíchaných prvků. |
| `selection.allowedCategories` | všechny | | Kategorie prvků, které smí přecházet. |
| `selection.categoryFrequency` | `{"small_vegetation": 2.5}` | | Násobič hustoty podle kategorie (kaktusy, třtina a dýně jsou vzácné). |
| `selection.excludedFeatures` | `[]` | | ID prvků, které nikdy nepřecházejí. |
| `fluid.*` | | | Samostatný přechod vodních prvků: `width` 8, `intensity`, `frequency`, `allowedFluids`, `allowFluidBodies` (jezera a prameny, vypnuto), `allowLandFeaturesInFluid`, `shoreRadius`, `shoreFrequency`, `shoreCategories`. |

### `debug`: ladění

| Klíč | Výchozí | Popis |
|---|---|---|
| `extremePreset` | `false` | **Extreme Preset**: mimo přechody se negenerují žádné vlastní prvky biomů, přechody tak jasně vyniknou. Jen pro testování. |
| `collectStats` | `true` | Sbírat statistiky pro `/btg stats`. |
| `logRejections` | `false` | Zapisovat do logu každé odmítnutí. |

---

## 7. Výchozí pravidla míchání

| Kombinace | Pravidlo |
|---|---|
| řeka, oceán, pláž, pobřeží | nemísí se (`noBlend`) |
| řeka | navíc bariéra (`barriers`) |
| poušť + badlands | mísí se jen spolu (`blendOnlyWithSelf`) |
| les → pláně | jen les do plání |
| bažina → les, bažina → pláně | jen bažina do lesa / plání |
| les + tajga | obousměrně, 80 bloků |
| les + džungle, les + louka, pláně + louka, pláně + savana | obousměrně |
| džungle + pláně | obousměrně, 48 bloků |
| tajga + sníh | obousměrně, 48 bloků |
| pláně + sníh, tajga + hory | obousměrně, 32 bloků |
| ostatní dvojice | globální pravidla, podobné skupiny 1.5× širší, odlišné klima užší |
| malé oceány (< 306 quartů) | nahradí se nejbližší řekou |

---

## 8. Recepty: časté úpravy

**Výraznější a širší přechody**
- `general.mixingDistance`: 96, `mixing.distribution.intensity`: 1.3, `advanced.selection.frequency`: 2.

**Jemný přechod**
- `mixingDistance` 24, `intensity` 0.7, `curve` `SMOOTHSTEP`.

**Kaktusy v džungli u pouště** (výchozí pravidla poušť izolují)
- smažte `desert+badlands` z `mixing.blendOnlyWithSelf`,
- nebo přidejte dvojici `{ "a": "desert", "b": "jungle", "mode": "A_INTO_B" }` a odstraňte poušť z `blendOnlyWithSelf`.

**Les nemá přeskakovat ani do bažiny**
- dvojice `{ "a": "forest", "b": "swamp", "mode": "DO_NOT_BLEND" }` dejte na začátek seznamu.

**Méně stromů v přechodu**
- `mixing.density.treesAndFoliage`: `AVERAGE`, nebo `"categoryFrequency": { "trees": 0.5 }`.

**Bez prolínání povrchu**
- `general.surfaceMixing`: `false`.

**Povrch pro modovaný biom**
- `"classification": { "overrides": { "mujmod:red_dunes": ["desert"] } }`, nebo
- `"blocks": { "mujmod:red_dunes": ["minecraft:red_sand", "minecraft:red_sandstone"] }`.

**Žádné nahrazování biomů**
- `general.biomeReplacing`: `false`.

**Malé březové lesy se mají stát okolním lesem**
- `replaceBiomes`: `minecraft:birch_forest`, `changeInto`: `NEAREST_SAME_GROUP`, `replaceMinSize`: 150.

**Vanilla barvy**
- `general.colorBlendDistance`: 0.

---

## 9. Řešení problémů

**Nevidím žádnou změnu.**
Mod mění jen nové chunky. Jděte do neprozkoumaného terénu nebo vytvořte nový svět. Zkontrolujte `general.enabled`.

**Mezi dvěma biomy nevzniká přechod.**
`/btg info` u hranice: když u souseda stojí „does not mix into this biome“, brání tomu pravidlo. Projděte `noBlend`, `blendOnlyWithSelf`, `barriers`, `mixSurfaceAndUnderground` a dvojice v `pairs` (směr `A_INTO_B` / `B_INTO_A`).

**Prvek souseda se v přechodu neobjevuje.**
1. `/btg features <sousední biom>`: je u prvku ✔? Když je ✘, uvidíte důvod.
2. `/btg stats`: důvody odmítnutí.
   - `surface block not allowed`: prvek potřebuje jiný povrch. Zapněte `general.surfaceMixing`.
   - `target biome already has it`: biom ten prvek už má.
   - `feature declined the spot`: vlastní pravidla prvku místo odmítla.
   - `overlaps another feature`: zapnutá kontrola `preventFeatureOverlap`.
   - `chunk placement limit`: zvyšte `performance.maxPlacementsPerChunk`.
3. Prvek je vzácný, nebo ho ředí Variety Balancing. Zvyšte jeho `frequency` nebo nastavte `varietyBalancing` na `DISABLED`.

**Ve světě je jen jeden biom.**
Zkontrolujte typ světa (**Superflat** a **Single Biome** mají jeden biom). Mod nahrazuje jen malé oblasti biomů ze seznamu `replaceBiomes`.

**Generování je pomalé.**
Snižte `general.mixingDistance` a `mixing.similarDistanceMultiplier`, vypněte `advanced.undergroundTransitions` nebo zvyšte `performance.undergroundSectionStride`.

**Po aktualizaci modu se mi resetovalo nastavení.**
Nová verze změnila strukturu configu. Vaše staré nastavení je v `config/biometransitions.json.bak`.
