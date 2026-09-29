# Rdzeń 0.4: zakres i założenia

## Podział kodu

| Klasa | Zadanie |
|---|---|
| `Cpu` | Pobiera instrukcję, wybiera ARM/Thumb i dodaje diagnostykę błędów |
| `CpuState` | Przechowuje rejestry i flagi, przełącza banki, obsługuje CPSR/SPSR |
| `ArmInterpreter` | Rozpoznaje kodowanie ARM i kieruje je do metody danej rodziny |
| `ThumbInterpreter` | Rozpoznaje kodowanie Thumb i wykonuje jego rodziny instrukcji |
| `ArithmeticLogicUnit` | Oblicza wyniki i flagi NZCV |
| `BarrelShifter` | Oblicza przesunięcia, rotacje i bit carry-out |
| `InstructionInterpreter` | Współdzieli reguły odczytu niewyrównanego, BX i SWI |

Dekodery współdzielą jeden `CpuState` i jedną magistralę `Memory`. Ich ALU oraz
shiftery powstają w konstruktorze, bez tworzenia obiektów dla kolejnych instrukcji.
`Machine` i publiczne API `Cpu` zachowują dotychczasowy sposób użycia.

W nazwach operandów używamy roli (`destination`, `baseRegister`, `registerList`),
a skróty zostają przy nazwach sprzętowych, takich jak CPSR, SWI i ALU.

## CPU

Interpreter ARMv4T, krok instrukcji bez modelowania cykli zegara. `r15` wewnętrznie
przechowuje adres następnej instrukcji; odczyty operandu PC dodają offset pipeline.
ARM używa PC+8 (lub +12 przy przesunięciu rejestrowym / STR), Thumb PC+4.

Obsługiwane rodziny:

- ARM: wszystkie 16 operacji ALU, flagi NZCV, warunki, pełny barrel shifter,
  B/BL/BX, LDR/STR bajt/słowo, transfery halfword/signed, LDM/STM, SWP,
  MUL/MLA i mnożenie long, MRS/MSR, banki rejestrów i podstawowe wejście SWI.
- Thumb: formaty ARMv4T 1–19, w tym ALU, rejestry wysokie, loads/stores,
  SP/PC relative, PUSH/POP, multiple transfer, skoki i dwuczęściowy BL.
- SWI wymaga dostarczonego BIOS w `Memory.setBios`; bez niego zgłasza błąd.
  Powrót przez `MOVS pc,lr` przywraca SPSR. Nie ma implementacji BIOS HLE.

To pokrycie rodzin instrukcji, nie deklaracja pełnej zgodności. Puste listy
LDM/STM/PUSH/POP oraz wybrane nieprzewidywalne kombinacje operandów zatrzymują
wykonanie. Flaga C po MUL jest zachowywana (ARMv4 definiuje ją jako nieprzewidywalną).
Nie ma jeszcze IRQ scheduler, abortów, wyjątków undefined, cykli, waitstates
ani zachowania pipeline dla samomodyfikującego kodu. ARMv5/Thumb-2 nie są celem.

## Magistrala

EWRAM 256 KiB, IWRAM 32 KiB, palette 1 KiB, VRAM 96 KiB i OAM 1 KiB, ROM do
32 MiB. Mirroring RAM, obszar VRAM 0x18000..0x1ffff, trzy okna ROM.
Niewyrównane LDR rotują słowo; LDRH/LDRSH mają zachowanie ARM7 opisane w GBATEK.
STR/STRH i transfery blokowe wymuszają wyrównanie.

Bajtowy zapis palette/BG VRAM duplikuje bajt w półsłowie, a OAM i OBJ VRAM
ignorują taki zapis. `KEYINPUT` jest aktywne stanem niskim i tylko do odczytu.
Rejestry DISPCNT, BG2CNT i BG2 affine służą rendererowi.

Niezaimplementowane obszary/IO przerywają pracę zamiast udawać działanie timera
czy DMA. Brak save hardware, EEPROM, flash, RTC, ochrony BIOS, prefetch/open-bus
latch i niuansów magistrali kartridża. ROM poza rozmiarem zwraca wzorzec adresu.

## Obraz

`BitmapRenderer` tworzy snapshot BG2 (modes 3/4/5), obsługuje stronę obrazu,
paletę, kolor tła, forced blank i prostą transformację affine z BG2PA/B/C/D/X/Y.
Ma jeden bufor RGB888 i nie alokuje nowej tablicy co render.

Nie ma synchronizacji z CPU/scanline, VBlank, kafelków, OBJ, blendingu, okien,
mosaic ani bitmap wrap. Włączone nieobsługiwane efekty zgłaszają błąd.
Demo zapisuje rejestry transformacji — renderer nie dopisuje ich ukrycie.

## Java i wydajność

Kod źródłowy projektu targetuje Javę 21. Ścieżka wykonywania instrukcji używa
prymitywów/tablic i nie tworzy obiektów przy poprawnej instrukcji. `var` zmienia
wyłącznie składnię; eksporter rozwiązuje typy przed budowaniem wersji na BB.

Nie ma JIT/dynarec gościa ARM. To interpretacja ARM/Thumb w interpreterze/JVM
hosta. Czas demo na desktopie nie przewiduje FPS ani wydajności na Boldzie.

## Warstwa desktopowa

`LaunchCommand` parsuje argumenty do jednego z trzech rekordów: `Demo`, `Header`
lub `Run`. Sealed interface zamyka zbiór komend, więc `Main` rozpatruje wszystkie
warianty w `switch` z dopasowaniem rekordów. Wymaga to JDK 21 bez funkcji preview.

Parser nie czyta plików i nie uruchamia emulatora. `Main` osobno wykonuje program,
zapisuje PNG, prezentuje wyniki i uruchamia okno Swing. Wynik wykonania opisuje
rekord `ExecutionResult`. Cały pakiet desktopowy jest wyłączony z eksportu na BB.

## Testy

JUnit Jupiter jest zależnością o zakresie `test`. Nie trafia do JAR-a aplikacji
ani do wygenerowanego rdzenia. Surefire odkrywa klasy `*Test`, a parametryzacja
zapewnia osobny wynik dla każdego zestawu operandów i flag.

`LegacyCoreTest` ładuje wyeksportowany rdzeń przez `URLClassLoader` z rodzicem
platformowym: klasy hosta nie mogą zastąpić testowanych klas. Pełne powtórzenie
scenariuszy `core` jest dostępne w skrypcie konsolowym.

`RuntimeChecks` jest przenośną diagnostyką aplikacji MIDP. Pozostaje niezależne
od JUnit, ponieważ stary runtime BlackBerry nie może uruchamiać Jupiter.
`DemoRomTest` wykonuje te same kontrole także na JVM hosta.

## Punkty wejścia do źródeł

1. `Machine`: składa Cartridge, Memory, Cpu i BitmapRenderer.
2. `DemoRom`: zawiera konkretne instrukcje i ich komentarze asemblerowe.
3. `Cpu.step`: wybiera dekoder ARM lub Thumb i zapewnia diagnostykę.
4. `ArmInterpreter.execute` / `ThumbInterpreter.execute`: wybór rodziny instrukcji.
5. `ArithmeticLogicUnit` i `BarrelShifter`: wyniki obliczeń oraz flagi.
6. `LegacyExport`: czytelna kolejność odczytu AST, typowania i zapisu.
   `LegacySyntaxScanner` zbiera zmiany przed analizą typów, a `LegacyApiScanner`
   sprawdza rozpoznane metody po analizie. Rekordy opisują źródła i poprawki.
7. `BlackBerryMidlet`: uruchomienie tych samych klas w MIDP.

## Referencje

- [ARM7TDMI Technical Reference Manual](https://documentation-service.arm.com/static/5f4786a179ff4c392c0ff819?token=)
- [GBATEK — zachowana dokumentacja GBA](https://rust-console.github.io/gbatek-gbaonly/)
- [Tonc — bitmap modes](https://gbadev.net/tonc/bitmaps.html)
- [Maven Compiler — release](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html)

Kod napisano na potrzeby tego eksperymentu; nie zawiera cudzego BIOS ani komercyjnego ROM.
