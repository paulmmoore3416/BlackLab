"""Subnodes: focused areas inside a main node. Self-Reliance & Survival lives under ◆ Home, Family & Health
(with synapses to Life Safety), and each subnode starts with a careful primer. Safety-critical numbers follow the
official sources named in each primer (CDC/EPA, USDA, Ready.gov, NWS, U.S. Army field manuals)."""
from __future__ import annotations


def S(id, name, emoji, parent, mission, keywords, related=(), primer=""):
    return {"id": id, "name": name, "emoji": emoji, "parent": parent, "mission": mission, "keywords": list(keywords), "related": list(related), "primer": primer.strip()}


GROUP = "Self-Reliance & Survival"

SUBNODES = [
    S("offgrid", "Off-Grid Survival", "🏕️", "home", "Stay alive and comfortable without the grid: priorities, gear and the skills that tie every other survival subnode together.",
      ["off-grid", "off grid", "survival", "bug out", "bushcraft", "wilderness", "self-reliance", "homestead"], ["water", "shelter", "firstaid", "navigation", "power"], """
## Knowledge

### Priorities — the rule of threes (rough limits, not targets)
About **3 minutes** without air · **3 hours** without shelter in harsh cold or heat · **3 days** without water · **3 weeks** without food. Solve them in that order.

### When things go wrong: S.T.O.P.
**Stop** (don't panic-move) · **Think** (what do I have, what's the threat) · **Observe** (weather, terrain, daylight, people) · **Plan** (shelter, water, signal, then travel only if it improves your odds).

### The Ten Essentials
Navigation (map, compass, GPS) · headlamp + spare batteries · sun protection · first-aid kit · knife/multi-tool · fire (two ways) · emergency shelter · extra food · extra water + a way to treat it · extra clothing.

### Longer-term off-grid basics
Water source + treatment ([[◇ Water Filtration & Purification]]) · heat and cooking fuel stored safely · power ([[◇ Off-Grid Power]]) · food storage and growing ([[◇ Food Preservation & Canning]], [[◇ Gardening & Seed Saving]]) · sanitation (latrines ≥ 200 ft / 60 m from water) · communications ([[◇ Emergency Communications]]) · skills over gear.

### Signaling for rescue
Three of anything = distress (three fires, three whistle blasts). Mirror flashes, bright colors, ground-to-air signs (X = need medical help, V = need assistance), stay with the vehicle when stranded — it's easier to find than you are.
"""),
    S("plants", "Plant Knowledge & Foraging", "🌿", "home", "Know plants well enough to grow them, use them and — only with certain identification — eat them.",
      ["plant", "botany", "forag", "edible", "wild edible", "mushroom", "tree", "leaf", "flower", "weed", "herb", "poisonous"], ["garden", "offgrid", "firstaid"], """
## Knowledge

> ⚠️ **Never eat a wild plant or mushroom you can't identify with certainty from several features.** Many deadly plants have edible look-alikes. When in doubt, don't. Mushrooms: only with an expert and a local guide.

### Identify by features, not a single trait
Leaf arrangement (alternate / opposite / whorled) · leaf shape and margin · stem (round, square — mints are square; hollow; hairy) · flowers (petal count, symmetry) · fruit/seed · smell · habitat and season · sap. Confirm with two independent references.

### Deadly look-alikes to know first
- **Water hemlock** and **poison hemlock** (smooth stems with purple blotches, umbrella flower heads) vs. *wild carrot / Queen Anne's lace* (hairy stem, carrot smell). Hemlocks can kill in small amounts.
- **Death camas** vs. *wild onion* — no onion smell = don't eat it.
- **Foxglove** vs. *comfrey/borage* leaves early in the season.
- **Pokeweed** berries and mature plant are toxic.
- Mushrooms: *Amanita* (death cap, destroying angel) cause liver failure; symptoms are delayed.

### Reliable, common wild edibles (positively identified)
Dandelion (all parts) · broadleaf plantain · clover flowers · cattail (shoots, roots) · wild blackberries/raspberries · wood sorrel (small amounts) · acorns (leach tannins in changes of water first) · chickweed · lamb's quarters · pine needle tea (not yew — yew is deadly).

### Contact hazards
Poison ivy/oak ("leaves of three, let it be"; hairy vine) · poison sumac (wet areas) · wild parsnip and giant hogweed sap + sunlight cause burns. Wash with soap and cool water soon after contact.

### The Universal Edibility Test
U.S. Army survival manuals describe a step-by-step test for *unknown* plants over many hours; it's a last resort that does **not** work for mushrooms and doesn't protect against many toxins. Prefer known plants and stored food.
"""),
    S("sourdough", "Sourdough & Bread Baking", "🍞", "home", "Make a reliable starter and bake great bread with nothing but flour, water, salt and time.",
      ["sourdough", "starter", "levain", "bread", "bak", "dough", "flour", "yeast", "loaf", "proof"], ["ferment", "preserve"], """
## Knowledge

### Starter (7–10 days to establish)
1. Day 1: mix **50 g whole-wheat or rye flour + 50 g water** (unchlorinated, ~80 °F / 27 °C) in a clean jar. Cover loosely.
2. Every 24 h (then every 12 h once it's active): keep **25–50 g** of starter, discard the rest, feed equal weights flour and water (**1 : 1 : 1** starter:flour:water). Switch to bread flour if you like.
3. Ready when it **doubles within 4–8 hours** of a feed, smells pleasantly sour/yeasty, and is domed and bubbly. (Float test is optional.)
4. Days 2–4 often bubble then go quiet — normal; keep feeding. Hooch (grey liquid) = hungry; pour off and feed.
Store in the fridge between bakes; feed weekly; take it out and feed 1–2 times before baking.

### A dependable country loaf (one loaf)
| Ingredient | Weight | Baker's % |
|---|---|---|
| Bread flour (10–20 % whole wheat optional) | 500 g | 100 % |
| Water | 350–375 g | 70–75 % |
| Active starter | 100 g | 20 % |
| Salt | 10 g | 2 % |

1. **Mix** flour + most of the water; rest 30–60 min (autolyse). Add starter, salt and the rest of the water; squeeze to combine.
2. **Bulk ferment** at 75–78 °F (24–26 °C) for **4–6 h**: 3–4 sets of stretch-and-folds every 30 min in the first 2 h. Done when risen ~50 %, domed, jiggly, with bubbles on the sides.
3. **Pre-shape**, rest 20–30 min, **shape** tight, place seam-up in a floured basket.
4. **Cold proof** 8–16 h in the fridge (flavor + easy scoring).
5. **Bake** in a Dutch oven preheated to **500 °F / 260 °C**: score, lid on 20 min; lid off at **450 °F / 230 °C** for 20–25 min until deep brown and **205–210 °F (96–99 °C)** inside. Cool at least 1 hour before cutting.

### Troubleshooting
Dense/gummy → under-fermented or cut too early · flat, spreading → over-fermented or weak shaping · pale → oven not hot enough · huge holes under the crust → under-proofed. Temperature changes everything: warmer = faster.

### Use the discard
Pancakes, crackers, flatbreads, waffles. Discard keeps a week in the fridge.
"""),
    S("water", "Water Filtration & Purification", "💧", "home", "Always have safe water: find it, store it, and make questionable water safe to drink.",
      ["water", "filter", "filtration", "purif", "boil", "bleach", "chlorine", "disinfect", "well", "rain barrel", "sodis", "giardia"], ["offgrid", "disaster", "firstaid"], """
## Knowledge

### Store
**1 gallon (≈ 4 L) per person per day** for drinking and sanitation; at least **3 days** for evacuation and **2 weeks** at home (Ready.gov). Food-grade containers, cool and dark; replace stored tap water every 6 months.

### Make it safe (CDC / EPA guidance)
1. **Clear it first:** let it settle, then strain through a clean cloth or coffee filter. Cloudy water shields germs.
2. **Boil** — the most reliable: a **rolling boil for 1 minute**; **3 minutes above 6,500 ft (2,000 m)**. Cool, store covered.
3. **Chlorine bleach** (plain, unscented, 6 % or 8.25 % sodium hypochlorite): **8 drops of 6 % or 6 drops of 8.25 % per gallon** of clear water (double for cloudy or very cold water). Stir, wait **30 minutes**; it should smell faintly of chlorine — if not, repeat once and wait 15 more minutes.
4. **Filters:** a pore size of **1 micron absolute or smaller** removes *Giardia* and *Cryptosporidium*; **0.1–0.4 µm** (hollow-fiber/ceramic "microfilters") also removes bacteria. **Viruses** need disinfection as well (boil, chlorine, UV) unless the filter is rated as a purifier.
5. **Tablets:** chlorine dioxide also works on *Cryptosporidium* (follow the label's contact time, often up to 4 h); iodine and chlorine tablets don't reliably kill *Crypto*.
6. **SODIS** (WHO-recognized): clear PET bottles ≤ 2 L, clear water, **6 hours of full sun** (2 days if cloudy).

Boiling and disinfection don't remove chemicals, fuel or heavy metals — find another source if water may be chemically contaminated.

### Improvised gravity filter
Layers in a container (top → bottom): cloth, fine sand, charcoal from your fire (not briquettes), gravel. It clears water; **it does not make it safe** — still boil or disinfect.
"""),
    S("fielddress", "Field Dressing & Game Care", "🦌", "home", "Turn a legal harvest into safe, clean meat: field dressing, cooling, transport and processing.",
      ["field dress", "gut", "hunt", "deer", "venison", "game", "carcass", "butcher", "skinning", "elk", "hog", "rabbit", "processing"], ["preserve", "firstaid", "offgrid"], """
## Knowledge

> Follow your state's hunting regulations: tag the animal **before** moving it, and keep any required evidence of sex or species. In chronic wasting disease (CWD) zones, follow carcass-transport rules and test deer before eating.

### Kit
Sharp fixed blade (+ a gut-hook if you like) · sharpener · nitrile or field-dressing gloves · zip ties/string · game bags · paper towels · water · headlamp · bone saw for larger game · cooler or ice.

### Field dressing a deer (the gut-pile method)
1. **Safety & position:** confirm the animal is dead (approach from behind; touch the eye with a stick). Gloves on. Lay it on its back, head slightly uphill.
2. **Free the rectum:** cut around the anus ("core the bung"), pull it out a little and tie it off so nothing contaminates the meat.
3. **Open the belly:** pinch the skin and make a shallow cut just in front of the pelvis. With two fingers inside under the blade (**edge up**), cut toward the breastbone **without puncturing the stomach or intestines**.
4. **Diaphragm & windpipe:** cut the diaphragm free around the ribs; reach up into the chest cavity and cut the windpipe and esophagus as far forward as you can.
5. **Remove:** roll the animal on its side and pull the organs out together, pulling the tied-off rectum back through the pelvis. Keep heart and liver in a clean bag if you'll eat them (inspect for spots or abscesses — discard if abnormal).
6. **Cool fast:** prop the cavity open with a stick, wipe out blood and debris (avoid soaking with water unless you'll dry it), hang in the shade or pack ice in game bags. Get the meat **below 40 °F (4 °C)** within hours; skinning speeds cooling.

### Food safety & disease
Wash hands and tools; keep meat clean and dry. Don't eat an animal that looked sick. In CWD areas, avoid the brain, spinal cord, eyes, spleen and lymph nodes; bone out the meat and don't saw through the spine. Rabbits and hares can carry **tularemia** — always wear gloves; cook all wild game to **160 °F (71 °C)** (ground) / per USDA guidance.

### Processing
Age (hang) only when you can hold 34–37 °F (1–3 °C). Trim fat and silver skin (wild fat tastes off), separate muscle groups, label and freeze (vacuum-sealed keeps 1–2 years), or can/cure using tested methods ([[◇ Food Preservation & Canning]]).
"""),
    S("firstaid", "First Aid & Wilderness Medicine", "⛑️", "home", "Keep someone alive until help arrives: bleeding, breathing, CPR, cold, heat and injuries far from care.",
      ["first aid", "cpr", "tourniquet", "bleeding", "wound", "burn", "fracture", "hypothermia", "heat stroke", "splint", "aed", "choking"], ["safety", "offgrid", "water"], """
## Knowledge

> General first-aid knowledge only — take a certified class (Red Cross, AHA, Wilderness First Aid) and practice. Call 911 when you can.

- **Scene safety first**, then check: responsive? breathing? bleeding?
- **Hands-only CPR** (adults): call 911, push hard and fast in the center of the chest — **100–120 per minute, at least 2 in (5 cm) deep**, full recoil. Use an AED as soon as one arrives and follow its prompts.
- **Severe bleeding:** firm direct pressure; pack deep wounds with gauze and press; for life-threatening limb bleeding, a **tourniquet 2–3 in above the wound** (not on a joint), tighten until bleeding stops, note the time, don't loosen.
- **Choking (adult):** 5 back blows and 5 abdominal thrusts, repeat; CPR if they become unresponsive.
- **Hypothermia:** get out of wind and wet, remove wet clothing, insulate from the ground, warm the core (blankets, warm sweet drinks if alert); handle gently.
- **Heat stroke** (hot, confused, may have stopped sweating): **emergency** — cool immediately (cold water immersion or soak + fan), call 911.
- **Burns:** cool running water **10–20 minutes**, cover loosely; no ice, butter or ointments on serious burns.
- **Fractures/sprains:** immobilize the joint above and below; check circulation beyond the splint.
- **Kit:** gauze, pressure bandage, tourniquet, tape, gloves, trauma shears, splint material, antiseptic wipes, blister care, personal medications, a CPR mask.
"""),
    S("preserve", "Food Preservation & Canning", "🥫", "home", "Store food safely for months and years: canning, drying, freezing, curing and rotation.",
      ["canning", "pressure canner", "water bath", "dehydrat", "preserv", "jerky", "freeze", "food storage", "pantry", "botulism", "jam", "pickl"], ["ferment", "garden", "fielddress"], """
## Knowledge

> Use **tested recipes** — the USDA Complete Guide to Home Canning (in this library) or the National Center for Home Food Preservation. Don't improvise canning times, jar sizes or thickeners.

- **Acid decides the method.** High-acid foods (pH ≤ 4.6: most fruits, jams, pickles, acidified tomatoes) → **boiling-water bath**. Low-acid foods (vegetables, beans, meat, poultry, fish, soups) → **pressure canner only**, because *Clostridium botulinum* spores survive boiling.
- **Altitude:** add time (water bath) or pressure (pressure canning) above 1,000 ft per the tested recipe's table.
- **Seals:** lids should be concave and not flex after 12–24 h; refrigerate and use any jar that didn't seal. Discard bulging, leaking, spurting or off-smelling jars **without tasting**.
- **Dehydrating:** 125–145 °F for produce; jerky should reach **160 °F (meat) / 165 °F (poultry)** before or during drying (USDA).
- **Freezing:** 0 °F (−18 °C) keeps food safe indefinitely; quality is best within months. Blanch vegetables first.
- **Long-term pantry:** white rice, dried beans, rolled oats, wheat berries, sugar, salt, honey keep for years when dry, cool and sealed (mylar + oxygen absorbers, or food-grade buckets). Rotate: first in, first out.
"""),
    S("comms", "Emergency Communications", "📻", "home", "Keep in touch and informed when phones and power fail.",
      ["radio", "ham", "gmrs", "frs", "noaa weather radio", "communication", "antenna", "repeater", "walkie", "satellite messenger"], ["disaster", "weather", "offgrid"], """
## Knowledge

- **Plan with PACE:** Primary (cell/text) · Alternate (text-only, messaging apps on Wi-Fi) · Contingency (GMRS/ham radio, out-of-state contact) · Emergency (satellite messenger/PLB, 911). **Text before you call** — texts get through congested networks.
- **NOAA Weather Radio:** 162.400–162.550 MHz, with SAME alerts for your county. Every home should have one with batteries.
- **FRS** (no license, 2 W on shared channels) for short range; **GMRS** (FCC license, no exam, covers your family) adds power and repeaters.
- **Amateur (ham) radio:** Technician license (35-question exam) opens VHF/UHF and local repeaters; General opens HF for long distance. Local ARES/RACES groups support emergency communications.
- **Satellite messengers / PLBs:** work anywhere with sky view; a PLB goes straight to search and rescue.
- **Family plan:** an out-of-area contact everyone calls, meeting places, and a printed contact card in every bag.
"""),
    S("navigation", "Land Navigation", "🧭", "home", "Know where you are and get where you're going with a map and compass — no signal needed.",
      ["navigation", "compass", "map", "topo", "contour", "gps", "bearing", "declination", "orienteering", "lost"], ["offgrid", "weather"], """
## Knowledge

- **Read the map:** scale (1:24,000 USGS: 1 in = 2,000 ft), contour interval, legend; contour lines close together = steep; V's point uphill in valleys.
- **Declination:** the difference between magnetic and true north for your area (on the map margin or NOAA's calculator). Set it on an adjustable compass, or add/subtract consistently.
- **Take a bearing** from the map (edge of compass along your route, rotate the housing to align with north-south lines) and follow it by keeping the needle "red in the shed".
- **Pace count:** learn your pace per 100 m on known ground (often ~60–70 double steps) and adjust for slopes and brush.
- **Techniques:** terrain association · handrails (follow a stream or ridge) · catching features/backstops · aiming off (deliberately aim to one side so you know which way to turn) · attack points.
- **No compass:** shadow-stick method (mark shadow tips 15 min apart; the line runs roughly west → east) · Polaris for north at night.
- **Lost?** S.T.O.P. ([[◇ Off-Grid Survival]]); stay put if people know your plan.
"""),
    S("shelter", "Shelter & Fire", "🔥", "home", "Stay warm and dry: build or improvise shelter and make fire safely in any weather.",
      ["shelter", "fire", "tarp", "tinder", "kindling", "ferro rod", "debris hut", "insulation", "sleeping bag", "campfire", "wood stove"], ["offgrid", "weather", "firstaid"], """
## Knowledge

### Shelter
- **Insulate from the ground first** — the ground steals heat faster than air (pads, boughs, leaves 6+ in deep).
- Site: flat, dry, out of wind, away from dead trees and limbs ("widowmakers"), dry washes and avalanche paths.
- Quick builds: tarp A-frame or lean-to (wind on the closed side) · debris hut (small, a ridgepole and 2–3 ft of leaves) · snow trench. Smaller = warmer.

### Fire
- **Fire triangle:** heat + fuel + oxygen. Prepare everything before you strike: **tinder** (dry grass, birch bark, cotton + petroleum jelly) → **kindling** (pencil-lead to pencil-thick) → **fuel** (wrist-thick and up).
- Lays: teepee for fast heat, log cabin for cooking coals. Ignition: lighter (best), ferro rod, waterproof matches.
- Wet weather: split wood to reach dry centers; dead branches still on trees are drier than ground wood.
- **Safety:** clear 10 ft around, keep water nearby, drown–stir–feel before leaving. Never use fuel-burning heaters, grills or generators in a tent, shelter, home or garage — **carbon monoxide** kills.
"""),
    S("knots", "Knots & Rope Work", "🪢", "home", "A small set of reliable knots for loads, shelter, rescue and everyday tasks.",
      ["knot", "rope", "cordage", "paracord", "hitch", "bowline", "lashing", "splice"], ["shelter", "offgrid", "tools"], """
## Knowledge

| Knot | Use | Note |
|---|---|---|
| **Bowline** | fixed loop that won't slip | easy to untie after loading; back it up for life safety |
| **Figure-eight follow-through** | strong fixed loop | the climbing standard; check the "dress" |
| **Clove hitch** | quick attachment to a post/rail | can slip under changing load; add half hitches |
| **Taut-line hitch** | adjustable tension (tent/tarp lines) | slides to tighten, grips under load |
| **Trucker's hitch** | 3:1 tensioning for loads | the go-to for strapping cargo |
| **Sheet bend** | joins two ropes, even of different sizes | double it for slippery rope |
| **Square (reef) knot** | bundles and bandages only | **not** for joining ropes under load |
| **Prusik** | friction hitch that grips a rope | ascending, backups, adjustable anchors |
| **Two half hitches** | tie off to a ring or post | simple and secure |

Rope care: keep it dry and out of sunlight, inspect for cuts and glazing, melt the ends of synthetic rope.
"""),
    S("power", "Off-Grid Power", "🔋", "home", "Keep lights, fridge, phones and radios running with solar, batteries and generators — safely.",
      ["solar", "battery", "inverter", "generator", "lifepo4", "charge controller", "off-grid power", "transfer switch", "kwh", "watt"], ["offgrid", "facility", "comms"], """
## Knowledge

1. **Load audit:** list each device's watts × hours/day = **Wh/day**. (A fridge ~1–2 kWh/day; LED lights ~10 W each; phone ~15 Wh.)
2. **Battery bank:** usable Wh ≥ daily Wh × days of autonomy. **LiFePO4** can use ~80–100 % of capacity for thousands of cycles; **lead-acid** should stay above 50 %.
3. **Solar array:** panel watts ≈ daily Wh ÷ peak sun hours (winter, your location — NREL maps) ÷ 0.75 (losses). Use an MPPT charge controller sized for array current.
4. **Inverter:** continuous rating > your simultaneous loads; surge rating for motors (fridges, pumps start at 3–6× running watts). Pure sine wave for electronics.
5. **Generators — safety:** **outdoors only, at least 20 ft from doors and windows**, never in a garage, with CO alarms in the home. Connect to house wiring only through a **transfer switch or interlock** installed by an electrician — backfeeding can kill utility workers.
6. Fuel: stabilize gasoline, rotate every 6–12 months; propane stores indefinitely.
"""),
    S("garden", "Gardening & Seed Saving", "🌱", "home", "Grow food that keeps coming back: soil, planting calendar, high-calorie crops and saving your own seed.",
      ["garden", "seed", "soil", "compost", "harvest", "transplant", "raised bed", "heirloom", "frost date", "crop rotation", "orchard"], ["plants", "preserve", "home"], """
## Knowledge

- **Start with soil:** a soil test (your county extension office) for pH and nutrients; most vegetables like pH 6.0–7.0. Add compost every season.
- **Know your zone and frost dates** (USDA Plant Hardiness Zone Map + local last/first frost) and plant to them; succession-plant quick crops every 2–3 weeks.
- **Calorie crops** for self-reliance: potatoes, sweet potatoes, dry beans, winter squash, corn, sunflowers. **Nutrient crops:** greens, tomatoes, peppers, carrots, onions.
- **Rotation:** don't repeat the same family in a bed year after year (nightshades, brassicas, legumes, cucurbits, alliums).
- **Seed saving:** save from **open-pollinated/heirloom** varieties (hybrids don't come true). Easy starters: beans, peas, lettuce, tomatoes (self-pollinating). Dry thoroughly, store cool, dark and dry in labeled envelopes; most stay viable 2–5 years.
- Water deeply and less often; mulch to hold moisture and suppress weeds.
"""),
    S("disaster", "Disaster Preparedness", "🌪️", "home", "Be ready for the hazards where you live: a plan, a kit, and knowing what to do when the alert comes.",
      ["disaster", "emergency", "tornado", "hurricane", "flood", "earthquake", "wildfire", "winter storm", "power outage", "evacuat", "go bag", "72-hour"], ["water", "comms", "firstaid", "safety"], """
## Knowledge

- **Kit (Ready.gov):** water (1 gal/person/day), non-perishable food, NOAA Weather Radio, flashlight, first-aid kit, batteries, whistle, dust masks, plastic sheeting + duct tape, wrench to shut off utilities, manual can opener, local maps, phone chargers/power bank, **medications**, copies of documents, cash. **3 days** to go, **2 weeks** at home.
- **Plan:** how you'll get alerts (WEA, NOAA radio), shelter spots per hazard, evacuation routes, meeting places, out-of-area contact, pets, people who need help.
- **Tornado:** lowest floor, interior room, away from windows; cover your head. **Watch** = conditions possible; **warning** = take shelter now.
- **Flood:** turn around, don't drown — **6 in of moving water** can knock you down, **12 in** can float a car.
- **Earthquake:** drop, cover, hold on. **Wildfire:** leave early, N95 for smoke. **Winter storm / outage:** layers, one warm room, never use grills or generators indoors.
- At work: your facility's hazard vulnerability analysis and emergency operations plan — see [[◆ Life Safety & Compliance]].
"""),
    S("tools", "Tools & Repair", "🛠️", "home", "Fix what you own: the core toolkit, sharp edges, and repair know-how for home, vehicle and equipment.",
      ["tool", "repair", "fix", "sharpen", "knife", "saw", "drill", "wrench", "multimeter", "weld", "maintenance"], ["knots", "power", "vehicles"], """
## Knowledge

- **Core toolkit:** tape measure, utility knife, screwdriver set (incl. bits), pliers (slip-joint, needle-nose, locking), adjustable + combination wrenches, socket set, hammer, level, cordless drill/driver, stud finder, multimeter, voltage tester (non-contact), flashlight, duct tape, zip ties, assorted fasteners.
- **Sharpening:** keep a consistent angle (~15–20° per side for kitchen/pocket knives); coarse → fine stone, then strop; a sharp tool is a safe tool.
- **Electrical safety:** turn off the breaker **and** verify dead with a tester before touching; GFCI near water; when in doubt, call a licensed electrician.
- **Repair mindset:** find the model number, look it up (iFixit and the Q&A libraries here), photograph before disassembly, bag and label screws.
"""),
    S("weather", "Weather Reading", "⛈️", "home", "Read the sky and the instruments to see weather coming and act before it arrives.",
      ["weather", "cloud", "barometer", "storm", "lightning", "forecast", "wind", "front", "thunder"], ["disaster", "navigation", "comms"], """
## Knowledge

- **Clouds:** towering **cumulonimbus** (anvil top) = thunderstorms; lowering, rotating wall clouds = possible tornado; high **cirrus** thickening to cirrostratus and lowering → rain within 12–24 h; mammatus under an anvil = strong storm.
- **Pressure:** a fast-falling barometer (several hPa/mb in 3 h) signals a storm or front; rising pressure → clearing.
- **Wind shifts** and a sudden temperature drop often mark a cold front's arrival.
- **Lightning:** *When thunder roars, go indoors* (NWS) — no place outside is safe; wait **30 minutes** after the last thunder. If caught out, avoid peaks, open fields, water and tall isolated trees.
- **Alerts:** **Watch** = be ready; **Warning** = act now. NOAA Weather Radio and Wireless Emergency Alerts.
"""),
    S("ferment", "Fermentation", "🫙", "home", "Preserve and transform food with microbes: sauerkraut, pickles, yogurt, kombucha, vinegar.",
      ["ferment", "sauerkraut", "kimchi", "brine", "pickle", "yogurt", "kombucha", "kefir", "vinegar", "lacto"], ["sourdough", "preserve"], """
## Knowledge

- **Lacto-fermented vegetables:** salt at **2 % of the vegetable weight** for sauerkraut (massaged until it releases brine), or a **2–3.5 % brine** (20–35 g salt per liter) for whole/cut vegetables. Keep everything **under the brine** (weight), 65–75 °F (18–24 °C), loosely covered. Taste from day 3–7; refrigerate when it's sour enough.
- **Normal vs bad:** a flat white film (kahm yeast) can be skimmed; **fuzzy, colored mold, slime or a rotten smell → discard**.
- **Yogurt:** heat milk to 180 °F (82 °C), cool to 110 °F (43 °C), stir in 2 tbsp live yogurt per quart, hold 106–112 °F for 6–12 h.
- **Kombucha:** sweet tea + SCOBY + some mature kombucha (acidity protects it), 7–14 days at room temperature.
- **Safety:** use non-iodized salt and clean equipment; **garlic or herbs in oil** are a botulism risk at room temperature — refrigerate and use within days.
- Sourdough is a fermentation too — see [[◇ Sourdough & Bread Baking]].
"""),
]

SUB_BY_ID = {s["id"]: s for s in SUBNODES}
