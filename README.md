# BerryBoy Advance — 0.4

Eksperymentalny emulator GBA dla klasycznego BlackBerry (cel: Bold 9900 / BBOS 7.1).
Projekt deweloperski: **Linux, Java 21, Maven**. Rdzeń wykonuje ARM/Thumb i generuje
obraz własnego programu demonstracyjnego. Nie uruchamia jeszcze gier komercyjnych.

## Zmiany w 0.4

Testy używają **JUnit Jupiter 5.13.4** i standardowego Maven Surefire. Scenariusze
są rozdzielone według odpowiedzialności; ALU, warunki, przesunięcia, transfery i CLI
mają testy parametryzowane. Wspólne fixture’y przygotowują ROM i pamięć, a `@TempDir`
izoluje pliki eksportera. Nie ma już własnego runnera `AllTests` ani liczników asercji.

CLI ma osobny parser `LaunchCommand`, `sealed interface`, rekordy i dopasowanie
rekordów w `switch` z Javy 21. `Main` oddziela wykonanie, eksport obrazu i prezentację.
Błędne opcje oraz niepoprawny budżet instrukcji są odrzucane przed otwarciem ROM-u.

Podział rdzenia z 0.3 pozostaje: `Cpu`, `CpuState`, dekodery ARM/Thumb, ALU i shifter.
Do czytania na telefonie otwórz **BerryBoy-Source.html**. Zawiera źródła, skróty do
klas i testów, skoki do metod, wyszukiwarkę i regulację tekstu. Działa offline.

## Start na Linuksie

Wymagane: JDK 21 i Maven 3.9.x (Maven musi działać na JDK 21; sprawdź `mvn -v`).

```bash
mvn test                       # testy JUnit 5
mvn verify                     # testy i zbudowanie JAR-a
mvn compile exec:java
```

Ostatnia komenda otwiera okno desktopowe z obrazem GBA. Bez okna:

```bash
mvn compile exec:java -Dexec.args="demo --headless"
```

Rezultaty: `target/demo.png`, `target/demo.gba`. Demo wykonuje 200 000 instrukcji:
ARM ustawia tryb bitmapowy, BX przechodzi do Thumb, pętla zapisuje 38 400 pikseli
przez magistralę pamięci. Na końcu PC zatrzymuje się w pętli pod `0x080000FA`.
Wygenerowany ROM ma własny kod i nagłówek deweloperski, bez logo Nintendo; testuje
bezpośrednie wejście do kartridża, a nie boot przez oryginalny BIOS.

```bash
mvn compile exec:java -Dexec.args="header /sciezka/plik.gba"
mvn compile exec:java -Dexec.args="run /sciezka/homebrew.gba 200000 --headless"
```

`run` jest narzędziem diagnostycznym: nieobsłużony sprzęt/opkod zatrzymuje emulację.

## Testy

```bash
mvn -Dtest=ArmArithmeticTest test
mvn -Dgroups=core test
mvn -Dgroups=build test
mvn -Dgroups=desktop test
```

Surefire zapisuje raporty w `target/surefire-reports`. `mvn test` obejmuje także
`LegacyCoreTest`: eksport całego rdzenia, kompilację wygenerowanych źródeł i osiem
przenośnych kontroli w izolowanym classloaderze. Nie wymaga to SDK BlackBerry.

`./test.sh` jest skrótem do `mvn verify`. Opcjonalny skrypt
`tools/test-with-console.sh` uruchamia tę samą kolekcję testów przez oficjalny
JUnit Platform Console, a następnie powtarza testy `core` na wygenerowanych klasach.

Podział scenariuszy, wymagania i przykłady: [docs/TESTING.md](docs/TESTING.md).

## Java 21 na starym BB — jak to działa

**BBOS 7.1 nie jest Javą 7.1 ani Javą 1.7.** Stary runtime nie wykonuje bytecode
Java 21. W repo jest zatem jeden współdzielony rdzeń w nowoczesnych źródłach,
a build generuje jego zgodną wersję:

1. Maven / JDK 21 kompiluje projekt i uruchamia testy na komputerze.
2. `LegacyExport` analizuje źródła przez javac AST i typowanie.
3. Zamienia lokalne `var` na rzeczywiste typy oraz proste `return switch (...)`
   na klasyczny `switch` z `return` w każdej gałęzi.
4. Stary kompilator kompiluje **wygenerowane źródła**, używając wyłącznie API SDK BB.
5. RAPC tworzy `.cod`; właściwy runtime BB interpretuje ten wynik.

```bash
mvn compile exec:java@legacy-export
```

To **wąski eksporter użytej składni, nie pełny kompilator Java 21 → BB**. Lambdy,
rekordy, generyki, pattern matching, kolekcje i nowe API JDK nie są automatycznie
przenoszone do wspólnego rdzenia. Eksporter odrzuca wiele takich konstrukcji;
autorytatywnym sprawdzeniem API pozostaje kompilacja z `net_rim_api.jar`.
Nowoczesne API oraz rekordy mogą być swobodnie używane w kodzie desktopowym
i narzędziach builda, których telefon nie ładuje.

## Właściwy symulator BlackBerry z Linuksa

```bash
export BB_JDE_HOME='/sciezka/wine/drive_c/BlackBerry JDE 7.1.0'
export BB_JDK6_HOME='/sciezka/wine/drive_c/Java/jdk1.6.0_45'
export BB_SIMULATOR_HOME='/sciezka/wine/drive_c/BlackBerry Simulators/9900'
mvn -Pbb-sim verify
```

Profil korzysta z istniejących windowsowych SDK, JDK 6 i symulatora **przez Wine**.
Build nie pobiera ich i nie zmienia firmware telefonu. Po załadowaniu `.cod`
otwórz w symulatorze **BerryBoy Advance**: MIDlet sam wykona osiem kontroli
w runtime urządzenia, wyświetli `PASS`/`FAILED`, czas i obraz demo.

Szczegóły i ograniczenia: [docs/BLACKBERRY.md](docs/BLACKBERRY.md).
Uruchomienie symulatora nie jest jeszcze automatycznym testem zakończonym w CI:
wynik MIDleta trzeba odczytać w jego oknie.

## Co zawiera kod

| Plik | Odpowiedzialność |
|---|---|
| `src/main/java/berryboy/Cpu.java` | Krótka pętla wykonania, wybór dekodera, diagnostyka |
| `src/main/java/berryboy/CpuState.java` | Rejestry, CPSR/SPSR, warunki, banki i wejście SWI |
| `src/main/java/berryboy/ArmInterpreter.java` | Dekodowanie i wykonanie instrukcji ARM |
| `src/main/java/berryboy/ThumbInterpreter.java` | Dekodowanie i wykonanie instrukcji Thumb |
| `src/main/java/berryboy/ArithmeticLogicUnit.java` | Operacje arytmetyczne i logiczne, flagi NZCV |
| `src/main/java/berryboy/BarrelShifter.java` | Przesunięcia i rotacje wraz z carry-out |
| `src/main/java/berryboy/InstructionInterpreter.java` | Wspólne operacje obu dekoderów |
| `src/main/java/berryboy/Memory.java` | RAM, ROM, mirroring, dostęp 8/16/32 bit, wybrane rejestry |
| `src/main/java/berryboy/BitmapRenderer.java` | Obraz BG2 w trybach bitmapowych 3/4/5 |
| `src/main/java/berryboy/DemoRom.java` | Oryginalny program ARM/Thumb rysujący obraz |
| `src/main/java/berryboy/RuntimeChecks.java` | Przenośne kontrole uruchamiane także w MIDlecie |
| `src/main/java/berryboy/desktop/LaunchCommand.java` | Typowane komendy i walidacja argumentów CLI |
| `src/main/java/berryboy/desktop/Main.java` | Wykonanie komendy, podgląd Swing i eksport PNG |
| `src/main/java/berryboy/build/LegacyExport.java` | Kolejność etapów eksportu i zapis źródeł |
| `src/main/java/berryboy/build/LegacySyntaxScanner.java` | Kontrola składni, obniżanie switch, typy lokalnego var |
| `src/main/java/berryboy/build/LegacyApiScanner.java` | Kontrola metod spoza projektu |
| `platform/midp/berryboy/BlackBerryMidlet.java` | Adapter MIDP do symulatora / urządzenia |
| `src/test/java/berryboy/` | JUnit 5: instrukcje, pamięć, obraz, eksporter i fixture’y |
| `src/test/java/berryboy/desktop/` | JUnit 5: poprawne i błędne komendy CLI |
| `tools/blackberry.sh` | Linux → Wine → legacy compiler → RAPC → simulator |
| `tools/test-with-console.sh` | Jupiter na hoście oraz powtórzenie testów na wyeksportowanym rdzeniu |

## Stan weryfikacji tego wydania

- **`mvn verify`: BUILD SUCCESS na Linuksie, JDK 21 i Maven 3.9.9.**
- **860 testów JUnit 5**, bez błędów i pominiętych przypadków. Liczba obejmuje
  osobne wykonania testów parametryzowanych; nie jest liczbą asercji.
- **834 testy rdzenia** powtórzone przez Jupiter na wyeksportowanych klasach.
- Eksporter wykonał 237 przekształceń składni. Wygenerowany rdzeń skompilowano
  z `--release 8` i uruchomiono na JVM hosta.
- CLI używa funkcji Java 21 bez preview. Wcześniejszy tryb budowania całego
  projektu na JDK 17 nie jest już obsługiwany.
- ROM demo i jego PNG są identyczne bajt w bajt z wersją 0.3.
- Zbudowano JAR aplikacji; JUnit pozostaje zależnością testową.
- **Kompilacja `.cod`, Wine, symulator i fizyczny Bold pozostają niezweryfikowane.**
- To własne testy regresyjne, nie certyfikacja ARM ani przejście zewnętrznego test ROM-u.

## Najbliższe etapy

1. Odpalić osiem kontroli w rzeczywistym BB runtime przez SDK/RAPC i symulator.
2. Uruchomić zewnętrzne test ROM-y ARM7TDMI; poprawić wykryte rozbieżności.
3. Dodać scheduler, przerwania, timery i DMA.
4. Rozwinąć PPU o tła kafelkowe i sprite'y, potem BIOS/HLE, save hardware i audio.

Dokładne granice emulacji: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Skrót do standardowej weryfikacji Maven/JUnit: `./test.sh`.
