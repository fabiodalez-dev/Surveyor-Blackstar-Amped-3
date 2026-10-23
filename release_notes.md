### Surveyor v1.9.0 — one browser for all 160 cabinets

Until now the CabRig page had two ways in. The 24 Blackstar factory cabinets sat in a dropdown at the top; the 136 shipped cabinets lived in a list at the bottom of the page, paged 24 at a time. One kind was always more visible than the other. Now both live in the same browser, and neither is favoured.

#### A card, and a browser behind it

At the top of the page a card shows the cabinet you have chosen: a drawing of it, brand · size · speaker · mic, a badge with its source, a small response curve, a favourite star, and "Browse all 160 cabinets".

The browser is a full-height sheet with its own scrolling list, grouped by brand in alphabetical order, so Blackstar sits between Behringer and Bogner. Each row carries a small drawing of the cabinet with its cones where they really are, a 2x15 upright and an 8x10 in a column, and the shape of its response.

#### One set of filters for both kinds

Favourites, source, brand, size, speaker, microphone, style, and a search that matches every word you type. Each value shows how many cabinets it would leave, given the other filters. A factory cabinet matches all six of its microphones, and if you pick it while a factory microphone is the filter, it opens on that microphone.

Choosing a factory cabinet brings back the usual microphone, axis, comparison and levelling controls. Choosing a shipped one shows its facts, its source and its fit against the pole bank, with Try, To a slot, To my profiles and In the lab. Writing to a slot now opens in place instead of at the bottom of the page. Shipped cabinets can be favourites too.

The old "Extra cabinets" section is gone. The cabinet lab, the WAV conversion and My profiles are unchanged.

#### What the filters know, and what they do not

The brand, size, speaker and microphone of each shipped cabinet are read from its own text: the title of the file, the folder names of its pack, and the gear list and description of the 44 source tones. A file's own title wins over its tone's gear list, which often names gear that is not in that file. About thirty ambiguous entries are settled by hand, each with its reason written next to it. `tools/cab_facets.py` repeats the derivation and `--check` fails if the shipped asset is out of date.

What the sources never say stays empty rather than guessed: 12 cabinets have no size (10 of them are mixes), 43 name no speaker, and 52 of the captured ones name no microphone. They are still in the browser; they simply do not appear under those filters.

#### Checked before release

86 unit tests, 6 of them new for the catalogue, and lint with no errors. The browser was tried on the emulator in English and Italian: opening it, combining filters, searching, and choosing both a factory and a shipped cabinet. It has not yet been tried on the AMPED 3 itself; auditions and slot writes go through the same calls as in 1.8.0, unchanged.

**Download `Surveyor-v1.9.0.apk` below to install.**
