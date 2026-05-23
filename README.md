# MacroEngineMod

Standalone Forge 1.8.9 client macro editor and runner.

The mod now uses the `github.xitadoptus` package/group and ships with an empty macro configuration. It does not install example macros or example event scripts.

## Build

Use Java 8 and the included Gradle wrapper. ForgeGradle 2.1 does not work with modern global Gradle installs, and Gradle versions old enough for ForgeGradle 2.1 cannot run on Java 22.

```bash
./gradlew setupDecompWorkspace
./gradlew build
```

On Windows, use `gradlew.bat setupDecompWorkspace` and `gradlew.bat build`.

Java 22 can stay installed for other projects, but this project should be built with a JDK 8 `JAVA_HOME`. Minecraft Forge 1.8.9 expects Java 8-compatible bytecode.

The mod jar is generated in `build/libs`.

To run the dev client from IntelliJ, use the Gradle run configuration named `Minecraft Client` or run:

```bash
./gradlew runClient
```

On Windows:

```bat
gradlew.bat runClient
```

Do not run an IntelliJ `Application` configuration with main class `GradleStart` unless its module classpath includes ForgeGradle's generated start classes. Running it as a Gradle task avoids the `Could not find or load main class GradleStart` error.

## In Game

Open the editor:

```text
/macro
/macros
/macroengine
```

Command helpers:

```text
/macro open
/macro help
/macro list
/macro reload
/macro save
/macro run <macro name>
```

Macro files are stored in `.minecraft/macroengine/macros`.

The main config file is `.minecraft/macroengine/macros/macros.json`. Saving also exports each macro script as a `.txt` file in the same folder so it can be run with `EXEC`.

## Config Format

```json
{
  "macros": [
    {
      "name": "Macro name",
      "key": "NONE",
      "script": "$${\nLOG(\"hello\");\n}$$",
      "enabled": true
    }
  ],
  "events": [
    {
      "event": "onJoinGame",
      "script": "$${\nLOG(\"joined\");\n}$$",
      "enabled": true
    }
  ]
}
```

`key` uses LWJGL keyboard names, for example `R`, `F`, `LCONTROL`, or `NONE`.

## Script Syntax

Scripts can be wrapped in `$${ ... }$$`. Statements are separated by new lines or semicolons.

```text
$${ 
SET(#count, 1);
LOG("Count: %#count%");
}$$
```

Commands use `NAME(arg1, arg2)`. Arguments can be quoted with single or double quotes. Variables are read with `%NAME%`. Direct assignment is supported for variable-like names:

```text
#count = 2 + 3 * 4;
&text = TRIM(" hello ");
```

Variables are stored case-insensitively. Prefixes such as `#`, `&`, and `@` are accepted for compatibility, but internally the clean variable name is used. Arrays use `[]`:

```text
SPLIT(",", "stone,dirt,diamond", &items[]);
LOG("%&items[0]%");
```

If a statement is not a known API command and starts with `/`, it is sent as chat. Unknown non-command statements print an unsupported action message.

## Events

Configured event scripts can use:

| Event | Locals |
| --- | --- |
| `onJoinGame` | Fires when a world/player becomes available. |
| `onWorldChange` | Fires when joining or leaving a world. |
| `onChat` | `%CHAT%`, `%CHATCLEAN%` |

Key macros receive `%KEYNAME%` and `%KEYID%` locals.

## Control Flow

| Command | Description |
| --- | --- |
| `IF(condition)` / `ELSEIF(condition)` / `ELSE` / `ENDIF` | Conditional blocks. Supports `=`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `&&`, `||`, and `!`. |
| `IFCONTAINS(value, search)` | True when `value` contains `search`. |
| `IFBEGINSWITH(value, search)` | True when `value` starts with `search`. |
| `IFENDSWITH(value, search)` | True when `value` ends with `search`. |
| `IFMATCHES(value, regex, &out, group)` | True when regex matches. Optionally stores a capture group. |
| `DO` / `LOOP` | Infinite loop capped internally to avoid runaway loops. |
| `DO(count)` / `LOOP` | Counted loop. |
| `DO` / `WHILE(condition)` | Runs while condition is true. |
| `DO` / `UNTIL(condition)` | Runs until condition is true. |
| `BREAK` | Exits the current loop. |
| `FOR(#i, start, end)` / `NEXT` | Numeric loop. Step is inferred from start/end. |
| `FOREACH(iterator)` / `NEXT` | Loops over an iterator row set. |
| `UNSAFE` / `ENDUNSAFE` | Compatibility block. It executes normally. |

## Chat, Logging, Timing, Files

| Command | Description |
| --- | --- |
| `ECHO(text)` | Sends chat or a command. Color codes using `&` are converted. |
| `LOG(text)` | Prints a colored client chat message. |
| `LOGRAW(json)` | Prints a raw JSON chat component, falling back to plain text. |
| `WAIT(value)` | Sleeps. Supports seconds, `ms`, and ticks with `t`. |
| `EXEC(file, reason, arg0, arg1...)` | Runs a script file from the macro folder. Extra args are locals `%0%`, `%1%`, etc. |
| `STOP()` | Stops the current macro. |
| `STOP(name)` | Stops running macros whose id contains `name`. |
| `TRACE(distance)` | Ray traces and fills hit variables. |
| `LOGTO(fileOrKey, text)` | Appends to a `.txt` file or stores a GUI property value. |

## Variables, Arrays, Strings

| Command | Description |
| --- | --- |
| `SET(&var, value)` | Sets a variable. Missing value means `true`. |
| `ASSIGN(&var, expression)` | Assigns an evaluated expression. Direct `&var = expression` uses this. |
| `UNSET(&var)` | Removes a variable. `UNSET(&array[])` removes an array, `UNSET(&array[2])` removes one element. |
| `TOGGLE(&var)` | Toggles boolean truthiness. |
| `INC(#var, amount)` / `DEC(#var, amount)` | Adds or subtracts an integer. Default amount is `1`. |
| `RANDOM(#out, max, min)` | Stores a random integer between min and max. Default min is `0`, max is `100`. |
| `IIF(condition, trueValue, falseValue, &out)` | Inline conditional assignment. |
| `TIME(&out, format)` | Stores current time using a Java `SimpleDateFormat`. |
| `LCASE(value, &out)` / `UCASE(value, &out)` | Changes string case. |
| `TRIM(value, &out)` | Trims leading and trailing whitespace. |
| `LEN(value, #out)` / `LENGTH(value, #out)` | Stores string length. |
| `SUBSTR(value, start, length, &out)` / `SUBSTRING(...)` | Stores a substring. |
| `STRIP(&out, value)` | Removes Minecraft formatting codes. |
| `REPLACE(&var, search, replacement)` | String replacement against an existing variable. |
| `REGEXREPLACE(&var, regex, replacement)` | Regex replacement against an existing variable. |
| `MATCH(value, regex, &out, group, default)` | Regex match into a variable. |
| `CONTAINS(value, search, &out, ignoreCase)` | Stores `true` or `false`. |
| `BEGINSWITH(value, search, &out, ignoreCase)` / `STARTSWITH(...)` | Stores `true` or `false`. |
| `ENDSWITH(value, search, &out, ignoreCase)` | Stores `true` or `false`. |
| `ENCODE(value, &out)` / `DECODE(value, &out)` | Base64 encode/decode. |
| `ARRAYSIZE(&array[], #out)` | Stores array size. |
| `PUSH(&array[], value)` | Appends to an array. |
| `POP(&array[], &out)` | Removes the last array element into a variable. |
| `PUT(&array[], value)` | Replaces the first empty slot or appends. |
| `INDEXOF(&array[], &out, value, caseSensitive)` | Finds an array value. |
| `JOIN(glue, &array[], &out)` | Joins an array. |
| `SPLIT(delimiter, value, &array[])` | Splits text into an array. Empty delimiter splits characters. |
| `ISRUNNING(name, &out)` | Stores whether a matching macro is running. |
| `CONFIG(name)` | Sets the current config label. |
| `IMPORT(value)` / `UNIMPORT(value)` | Adds/removes values from the `IMPORTS` store. |
| `STORE(type, value)` / `STOREOVER(type, value)` | Adds to a named store. `STOREOVER` removes duplicates first. |
| `PROMPT(&out, title, message, value, default)` | Stores `value` if present, otherwise `default`. |

## Math

Numeric expressions are supported in assignment values, including `+`, `-`, `*`, `/`, `%`, and parentheses.

| Command | Description |
| --- | --- |
| `SQRT(value, #out)` | Integer square root. |
| `ABS(value, #out)` | Absolute value. |
| `FLOOR(value, #out)` | Floor. |
| `CEIL(value, #out)` / `CEILING(...)` | Ceiling. |
| `ROUND(value, #out)` | Rounded integer. |
| `MIN(a, b, ..., #out)` | Minimum value. |
| `MAX(a, b, ..., #out)` | Maximum value. |
| `CLAMP(value, min, max, #out)` | Clamps a value. |

The same names can be used as expression functions: `ABS`, `FLOOR`, `CEIL`, `ROUND`, `MIN`, `MAX`, `CLAMP`, `LEN`, `TRIM`, `SUBSTR`, `CONTAINS`, `BEGINSWITH`, `STARTSWITH`, `ENDSWITH`, `IIF`, `ISRUNNING`, `ITEMID`, `ITEMNAME`, and `DISTANCE`.

## Input, Movement, Inventory

| Command | Description |
| --- | --- |
| `KEY(bind)` | Pulses a Minecraft key binding. |
| `KEYDOWN(bind)` / `KEYUP(bind)` | Holds or releases a Minecraft key binding. |
| `TOGGLEKEY(bind)` | Toggles a key binding pressed state. |
| `PRESS(keyName)` | Sends a raw key tick by LWJGL key name. |
| `TYPE(text)` | Opens chat prefilled with text. |
| `SPRINT()` / `UNSPRINT()` | Sets player sprint state. |
| `SLOT(slot)` | Selects hotbar slot. `1..9` are accepted. |
| `INVENTORYUP(amount)` / `INVENTORYDOWN(amount)` | Scrolls selected hotbar slot. |
| `PICK(item...)` | Selects the first matching hotbar item. |
| `BIND(binding, key)` | Rebinds a Minecraft key binding and saves options. |
| `GETSLOT(item, #out, start)` | Finds an inventory slot containing an item id/name. |
| `GETSLOTITEM(slot, #idOut, #countOut, #damageOut)` | Reads an inventory slot. |
| `SLOTCLICK(slot, button, shift)` | Clicks an open container slot. Button `0` is left, `1` is right. |
| `SLOTCLICKITEM(item, button, shift)` / `CLICKITEM(...)` | Clicks the first matching item in an open container. |
| `SETSLOTITEM(item, slot, amount)` | Creative-only slot item packet. |
| `CRAFT(item)` / `CRAFTANDWAIT(item)` | Compatibility fallback that selects an item. |
| `CLEARCRAFTING()` | Compatibility no-op. |

Key binding aliases include `attack`, `use`, `jump`, `sneak`, `sprint`, `forward`, `back`, `left`, `right`, `drop`, `inventory`, `chat`, `playerlist`, `tab`, and `pickblock`.

## World, Blocks, Aiming

| Command | Description |
| --- | --- |
| `ITEMID(item, #out)` | Converts an item descriptor to numeric id. |
| `ITEMNAME(id, &out)` | Converts numeric item id to registry name. |
| `GETITEMINFO(item, &name, #maxStack, &type, &drop)` | Reads basic item metadata. |
| `GETID(x, y, z, #idOut, #metaOut)` | Reads block id/meta at absolute coordinates. |
| `GETIDREL(x, y, z, #idOut, #metaOut)` | Reads block id/meta relative to the player. |
| `GETBLOCKINFO(x, y, z, #idOut, #metaOut, &nameOut, &registryOut)` | Reads block id, meta, localized name, and registry key. |
| `GETBLOCKINFOREL(x, y, z, #idOut, #metaOut, &nameOut, &registryOut)` | Relative version of `GETBLOCKINFO`. |
| `CALCYAWTO(x, z, #yawOut, #distanceOut)` | Calculates yaw and horizontal distance to a point. |
| `CALCPITCHTO(x, y, z, #pitchOut, #distanceOut)` | Calculates pitch and distance to a point. |
| `CALCAIMTO(x, y, z, #yawOut, #pitchOut, #distanceOut)` | Calculates yaw, pitch, and distance. |
| `DISTANCE(x, y, z, #out)` | Stores distance from player to coordinates. |
| `LOOK(yaw, pitch)` | Sets view angles. Prefix yaw/pitch with `+` or `-` for relative movement. |
| `LOOKS(yaw, pitch, ticks)` | Smooth view movement. Ticks accept numbers, `t`, `ms`, or `s`. |
| `AIMTO(x, y, z, ticks)` | Smoothly aims at coordinates. |

Item descriptors can be numeric ids, registry names such as `minecraft:stone`, short names such as `stone`, or descriptors with damage like `minecraft:wool:14`.

## Client and GUI

| Command | Description |
| --- | --- |
| `FOV(value)` | Sets FOV. |
| `GAMMA(value)` | Sets gamma. Values above `10` are treated as percentages. |
| `SENSITIVITY(value)` | Sets mouse sensitivity. Values above `10` are treated as percentages. |
| `VOLUME(value, category)` / `MUSIC(value, category)` | Sets sound volume. |
| `CHATHEIGHT(value)` / `CHATHEIGHTFOCUSED(value)` | Sets chat height. |
| `CHATOPACITY(value)` | Sets chat opacity. |
| `CHATSCALE(value)` | Sets chat scale. |
| `CHATWIDTH(value)` | Sets chat width. |
| `CHATVISIBLE(value)` | Sets chat visibility: full, system/commands, or hidden. |
| `FOG(chunks)` | Sets render distance or toggles between near/far. |
| `RESOURCEPACKS(pattern...)` | Selects resource packs matching names and reloads resources. |
| `RELOADRESOURCES()` | Reloads resources. |
| `SETRES(width, height)` | Sets display resolution. |
| `SHADERGROUP(path)` | Loads or clears an entity renderer shader group. |
| `CAMERA(value)` | Sets or cycles third person view. |
| `CLEARCHAT()` | Clears chat. |
| `TITLE(title, subtitle, fadeIn, stay, fadeOut)` | Displays a title. |
| `TOAST(text)` / `POPUPMESSAGE(text)` | Prints a client message. |
| `GUI(name)` | Opens a supported GUI. `chat` opens chat. |
| `SHOWGUI(name)` | Stores screen properties and opens a supported GUI. |
| `BINDGUI(slot, screen)` | Stores a GUI binding property. |
| `GETPROPERTY(control, property, &out)` | Reads a stored GUI property. |
| `SETPROPERTY(control, property, value)` | Stores a GUI property. |
| `SETLABEL(label, text, bind)` | Stores a label and optional label binding. |
| `DISCONNECT()` | Disconnects from the current world/server. |
| `RESPAWN()` | Respawns the player. |
| `PLACESIGN(line1, line2, line3, line4, notify)` | Queues sign text variables. |
| `PLAYSOUND(sound, volume)` | Plays a sound on the player. |

Volume categories: `MASTER`, `MUSIC`, `RECORDS`, `WEATHER`, `BLOCKS`, `HOSTILE`, `NEUTRAL`, `PLAYERS`, and `AMBIENT`.

Compatibility no-ops: `CHATFILTER`, `FILTER`, `PASS`, `MODIFY`, `REPL`.

## Built-In Variables

Variables are used as `%NAME%`. Most values are strings.

Player and motion:

```text
PLAYER DISPLAYNAME UUID HEALTH HUNGER SATURATION ARMOUR ARMOR LEVEL XP TOTALXP LIGHT
XPOS YPOS ZPOS XPOSF YPOSF ZPOSF MOTIONX MOTIONY MOTIONZ SPEED
YAW CARDINALYAW PITCH DIRECTION MODE GAMEMODE CANFLY FLYING ONGROUND INWATER
ISBURNING ISRIDING OXYGEN VEHICLE VEHICLEHEALTH
```

Held item, armor, and use state:

```text
ITEM ITEMCODE ITEMIDDMG ITEMNAME ITEMDAMAGE DURABILITY ITEMMAXDAMAGE MAXDAMAGE
DURABILITYPCT ITEMDURABILITYPCT STACKSIZE ATTACKPOWER ATTACKSPEED BOWCHARGE
COOLDOWN ITEMUSEPCT ITEMUSETICKS INVSLOT
BOOTSID BOOTSNAME BOOTSDAMAGE BOOTSDURABILITY
LEGGINGSID LEGGINGSNAME LEGGINGSDAMAGE LEGGINGSDURABILITY
CHESTPLATEID CHESTPLATENAME CHESTPLATEDAMAGE CHESTPLATEDURABILITY
HELMID HELMNAME HELMDAMAGE HELMDURABILITY
OFFHANDITEM OFFHANDITEMIDDMG OFFHANDITEMNAME OFFHANDITEMCODE OFFHANDDURABILITY
OFFHANDDAMAGE OFFHANDSTACKSIZE OFFHANDCOOLDOWN
```

Input:

```text
KEY_<name> ~KEY_<name> ~ALT ~CTRL ~SHIFT ~LMOUSE ~RMOUSE ~MIDDLEMOUSE
LMOUSE RMOUSE MIDDLEMOUSE SHIFT CTRL ALT
```

Client, server, and GUI:

```text
GUI CONTAINERSLOTS DISPLAYWIDTH DISPLAYHEIGHT SERVER SERVERNAME SERVERMOTD
ONLINEPLAYERS MAXPLAYERS FPS GAMMA FOV SENSITIVITY CAMERA
SOUND MUSIC RECORDVOLUME WEATHERVOLUME BLOCKVOLUME HOSTILEVOLUME NEUTRALVOLUME
PLAYERVOLUME AMBIENTVOLUME CONFIG SCREEN SCREENNAME SHADERGROUP
RESOURCEPACKS[] SHADERGROUPS[]
```

World and time:

```text
DATE TIME DATETIME TIMESTAMP BIOME DIMENSION DIFFICULTY LOCALDIFFICULTY
POTIONCOUNT RAIN TICKS TOTALTICKS DAY DAYTICKS DAYTIME SEED CHUNKUPDATES UNIQUEID
```

Ray trace and sign text:

```text
HIT HITX HITY HITZ HITSIDE HITID HITDATA HITNAME HITUUID
SIGNTEXT[0] SIGNTEXT[1] SIGNTEXT[2] SIGNTEXT[3]
```

`TRACE(distance)` refreshes the full hit variable set. Some hit variables also read directly from Minecraft's current `objectMouseOver`.

## Foreach Iterators

`FOREACH(iterator)` sets local variables for each row.

| Iterator | Row variables |
| --- | --- |
| `players` | `INDEX`, `PLAYER`, `PLAYERNAME`, `UUID` |
| `running` | `INDEX`, `MACRO`, `VALUE` |
| `env` | `INDEX`, `VARNAME`, `VALUE` |
| `effects` | `INDEX`, `EFFECT`, `EFFECTID`, `EFFECTDURATION`, `EFFECTAMPLIFIER` |
| `enchantments` | `INDEX`, `ENCHANTMENT`, `ENCHANTMENTID`, `ENCHANTMENTLEVEL` |
| `inventory` | `INDEX`, `SLOT`, `ITEM`, `ITEMID`, `ITEMCODE`, `ITEMNAME`, `STACKSIZE`, `DAMAGE`, `MAXDAMAGE`, `DURABILITY`, `DURABILITYPCT` |
| `hotbar` | Same as `inventory`, limited to slots `0..8`. |
| `container` / `slots` | Same item row fields, reading the open container. |
| `properties` | `INDEX`, `PROPERTY`, `VALUE` for the looked-at block. |
| `controls` | `INDEX`, `CONTROL`, `VALUE` for stored GUI properties. |

## Parameter Expansion

The engine supports a few legacy parameter tokens before parsing:

| Token | Value |
| --- | --- |
| `$$u` | First online player name, or local player name. |
| `$$i`, `$$i:d` | Held item id and damage. |
| `$$k` | First resource pack name. |
| `$$m` | First `.txt` macro file name. |
| `$$s` | Current shader group. |
| `$$f`, `$$h`, `$$p`, `$$t`, `$$w` | First value from the matching store. |
| `$$0` through `$$9` | First value from stores named `0` through `9`. |
| `$$<file>` | Inline include of a script file from the macro folder. |
| `$$[[a,b,c]]` | Expands to the first comma-separated value. |
